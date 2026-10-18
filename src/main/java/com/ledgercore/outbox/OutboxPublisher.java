package com.ledgercore.outbox;

import com.ledgercore.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reliable event delivery.
 *  - claim: a short transaction leases a batch (FOR UPDATE SKIP LOCKED) so several instances never process the same event;
 *  - dispatch: each event runs in its own transaction together with the inbox row, so a handler's effects and the
 *    "SENT" mark commit atomically; a failure rolls the handler's work back;
 *  - failure: attempts++ and exponential backoff; after max attempts the event is DEAD (dead-letter) for an operator to replay;
 *  - crash safety: a lease that expires (instance died mid-flight) makes the event claimable again.
 */
@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final List<OutboxHandler> handlers;
    private final AppProperties.Outbox cfg;
    private final Clock clock;
    private final MeterRegistry metrics;

    public OutboxPublisher(JdbcTemplate jdbc, PlatformTransactionManager txManager, List<OutboxHandler> handlers,
                           AppProperties props, Clock clock, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.handlers = handlers;
        this.cfg = props.outbox();
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${ledgercore.outbox.poll-interval-ms:1000}")
    public void poll() {
        if (!cfg.enabled()) return;
        try {
            processBatch();
        } catch (Exception e) {
            log.error("Outbox poll failed", e);
        }
    }

    /** Processes one batch; returns how many events were claimed. Public so tests and operators can drive it. */
    public int processBatch() {
        List<OutboxMessage> claimed = claim();
        for (OutboxMessage m : claimed) dispatch(m);
        return claimed.size();
    }

    private List<OutboxMessage> claim() {
        long leaseSeconds = Math.max(1, cfg.lease().toSeconds());
        List<OutboxMessage> rows = tx.execute(status -> jdbc.query(
                "UPDATE outbox_events SET status = 'PROCESSING', locked_until = now() + (? * interval '1 second') WHERE id IN ("
                        + "  SELECT id FROM outbox_events "
                        + "  WHERE (status IN ('PENDING','FAILED') AND next_attempt_at <= now()) "
                        + "     OR (status = 'PROCESSING' AND locked_until < now()) "
                        + "  ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED) "
                        + "RETURNING id, aggregate_type, aggregate_id, event_type, payload::text AS payload, attempts",
                (rs, i) -> new OutboxMessage(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getString("payload"), rs.getInt("attempts")),
                leaseSeconds, cfg.batchSize()));
        return rows == null ? List.of() : rows;
    }

    private void dispatch(OutboxMessage m) {
        try {
            tx.executeWithoutResult(status -> {
                for (OutboxHandler h : handlers) {
                    if (!h.supports(m.eventType())) continue;
                    // inbox: skip if this handler already applied this event (redelivery after a crash between commit and ack)
                    int fresh = jdbc.update("INSERT INTO processed_events (event_id, handler) VALUES (?, ?) ON CONFLICT DO NOTHING",
                            m.id(), h.name());
                    if (fresh == 1) {
                        try {
                            h.handle(m);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new HandlerFailure(e);
                        }
                    }
                }
                jdbc.update("UPDATE outbox_events SET status = 'SENT', processed_at = now(), locked_until = NULL, last_error = NULL WHERE id = ?", m.id());
            });
            metrics.counter("ledgercore.outbox.delivered").increment();
        } catch (Exception e) {
            recordFailure(m, e);
        }
    }

    private void recordFailure(OutboxMessage m, Exception e) {
        Throwable cause = e instanceof HandlerFailure && e.getCause() != null ? e.getCause() : e;
        int attempts = m.attempts() + 1;
        boolean dead = attempts >= cfg.maxAttempts();
        Duration delay = Backoff.delay(attempts, cfg.baseBackoff(), cfg.maxBackoff(), ThreadLocalRandom.current().nextDouble());
        Instant next = clock.instant().plus(delay);
        String error = String.valueOf(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        if (error.length() > 1000) error = error.substring(0, 1000);
        String finalError = error;
        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE outbox_events SET status = ?, attempts = ?, next_attempt_at = ?, locked_until = NULL, last_error = ? WHERE id = ?",
                dead ? "DEAD" : "FAILED", attempts, Timestamp.from(next), finalError, m.id()));
        metrics.counter(dead ? "ledgercore.outbox.deadlettered" : "ledgercore.outbox.retry").increment();
        if (dead) log.error("Outbox event {} ({}) moved to dead-letter after {} attempts: {}", m.id(), m.eventType(), attempts, finalError);
        else log.warn("Outbox event {} failed (attempt {}), retrying in {}: {}", m.id(), attempts, delay, finalError);
    }

    private static class HandlerFailure extends RuntimeException {
        HandlerFailure(Throwable cause) { super(cause); }
    }
}
