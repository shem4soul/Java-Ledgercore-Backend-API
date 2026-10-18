package com.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ledgercore.idempotency.IdempotencyService.Outcome;
import com.ledgercore.ledger.TransferDtos.TransferResponse;
import com.ledgercore.notification.NotificationSender;
import com.ledgercore.outbox.OutboxAdminService;
import com.ledgercore.outbox.OutboxPublisher;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Reliability: failures mid-flight leave no partial state, and failed downstream work is retried safely. */
@Import(FailureRecoveryIT.FlakySenderConfig.class)
class FailureRecoveryIT extends AbstractIT {

    /** A notification channel that can be told to fail N times, to exercise retries and the dead-letter queue. */
    static class FlakySender implements NotificationSender {
        final AtomicInteger failuresRemaining = new AtomicInteger();
        final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void send(UUID userId, String subject, String message) {
            attempts.incrementAndGet();
            if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0) throw new IllegalStateException("provider down");
        }
    }

    @TestConfiguration
    static class FlakySenderConfig {
        @Bean
        @Primary
        FlakySender flakySender() {
            return new FlakySender();
        }
    }

    @Autowired FlakySender sender;
    @Autowired OutboxPublisher publisher;
    @Autowired OutboxAdminService outboxAdmin;

    @BeforeEach
    void resetSender() {
        sender.failuresRemaining.set(0);
        sender.attempts.set(0);
    }

    private UUID eventFor(UUID transactionId) {
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?", UUID.class, transactionId);
    }

    private String statusOf(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private long notifications(UUID eventId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id = ?", Long.class, eventId);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- crash between the two legs of a transfer

    @Test
    void crashBetweenDebitAndCredit_rollsBackEverything_andTheSameKeyCanBeRetried() {
        Customer payer = customer(100_000);
        Customer payee = customer(0);
        String key = "crash-" + UUID.randomUUID();
        Long txBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Long.class);
        Long outboxBefore = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class);

        hooks.setAfterDebit(() -> { throw new IllegalStateException("simulated crash after debit"); });
        assertThatThrownBy(() -> send(payer, payee.account(), 30_000, key)).hasMessageContaining("simulated crash");

        assertThat(balance(payer.account())).as("debit was rolled back").isEqualTo(100_000);
        assertThat(balance(payee.account())).as("credit never happened").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Long.class)).isEqualTo(txBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).as("no event for a transfer that never happened").isEqualTo(outboxBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?", Integer.class,
                payer.user().id(), key)).as("key rolled back too").isZero();

        hooks.clear();
        Outcome<TransferResponse> retry = sendOutcome(payer, payee.account(), 30_000, key);
        assertThat(retry.replayed()).isFalse();
        assertThat(balance(payer.account())).isEqualTo(70_000);
        assertThat(balance(payee.account())).isEqualTo(30_000);
        assertThat(reconciliation.run().healthy()).isTrue();
    }

    // ---------------------------------------------------------------- outbox: retry, dead-letter, replay, crash, redelivery

    @Test
    void failedNotification_isRetriedWithBackoff_andEventuallyDeliveredExactlyOnce() {
        Customer payer = customer(10_000);
        Customer payee = customer(0);
        drainOutbox();                                          // ignore the seed deposits' events
        sender.failuresRemaining.set(2);                        // provider is down for the first two attempts

        TransferResponse r = send(payer, payee.account(), 1_000, "evt-" + UUID.randomUUID());
        UUID eventId = eventFor(r.transactionId());
        assertThat(statusOf(eventId)).isEqualTo("PENDING");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            publisher.processBatch();
            assertThat(statusOf(eventId)).isEqualTo("SENT");
        });

        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, eventId))
                .as("two failures were recorded before the success").isEqualTo(2);
        assertThat(notifications(eventId)).as("one notification each for payer and payee, never duplicated").isEqualTo(2);
        assertThat(balance(payer.account())).as("a failing downstream never affects the money").isEqualTo(9_000);
    }

    @Test
    void persistentFailure_goesToDeadLetter_andOperatorReplayDelivers() {
        Customer payer = customer(10_000);
        Customer payee = customer(0);
        drainOutbox();
        sender.failuresRemaining.set(1_000);                    // permanently broken

        TransferResponse r = send(payer, payee.account(), 1_000, "dead-" + UUID.randomUUID());
        UUID eventId = eventFor(r.transactionId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            publisher.processBatch();
            assertThat(statusOf(eventId)).isEqualTo("DEAD");    // max-attempts is 3 in the test profile
        });
        assertThat(notifications(eventId)).as("failed attempts left no partial side effects").isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE id = ?", String.class, eventId)).contains("provider down");

        publisher.processBatch();
        assertThat(statusOf(eventId)).as("dead events are not retried automatically").isEqualTo("DEAD");

        sender.failuresRemaining.set(0);                        // provider recovers; an operator replays the event
        outboxAdmin.retry(adminId(), eventId);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            publisher.processBatch();
            assertThat(statusOf(eventId)).isEqualTo("SENT");
        });
        assertThat(notifications(eventId)).isEqualTo(2);
    }

    @Test
    void anEventLeasedByACrashedWorker_isPickedUpAgainAfterTheLeaseExpires() {
        Customer payer = customer(10_000);
        Customer payee = customer(0);
        drainOutbox();
        TransferResponse r = send(payer, payee.account(), 500, "lease-" + UUID.randomUUID());
        UUID eventId = eventFor(r.transactionId());

        // a worker claimed it and died: PROCESSING with a lease that is still valid => nobody else may take it
        jdbc.update("UPDATE outbox_events SET status = 'PROCESSING', locked_until = now() + interval '10 minutes' WHERE id = ?", eventId);
        publisher.processBatch();
        assertThat(statusOf(eventId)).isEqualTo("PROCESSING");

        // the lease expires => it is reclaimed and delivered
        jdbc.update("UPDATE outbox_events SET locked_until = now() - interval '1 second' WHERE id = ?", eventId);
        publisher.processBatch();
        assertThat(statusOf(eventId)).isEqualTo("SENT");
        assertThat(notifications(eventId)).isEqualTo(2);
    }

    @Test
    void redeliveryOfAnAlreadyHandledEvent_doesNotRepeatTheSideEffect() {
        Customer payer = customer(10_000);
        Customer payee = customer(0);
        drainOutbox();
        TransferResponse r = send(payer, payee.account(), 500, "inbox-" + UUID.randomUUID());
        UUID eventId = eventFor(r.transactionId());

        // simulate: the handler already ran and committed, but the worker died before the event was marked SENT
        jdbc.update("INSERT INTO processed_events (event_id, handler) VALUES (?, 'notification')", eventId);
        publisher.processBatch();

        assertThat(statusOf(eventId)).isEqualTo("SENT");
        assertThat(sender.attempts.get()).as("the inbox suppressed the duplicate delivery").isZero();
        assertThat(notifications(eventId)).isZero();
    }
}
