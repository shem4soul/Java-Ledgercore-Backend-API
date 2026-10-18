package com.ledgercore.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Exposes outbox backlog and dead-letter size to Prometheus so alerts can fire on stuck events. */
@Component
public class OutboxMetrics implements MeterBinder {
    private final JdbcTemplate jdbc;

    public OutboxMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("ledgercore.outbox.backlog", this, m -> m.count("status IN ('PENDING','FAILED','PROCESSING')"))
                .description("Outbox events waiting to be delivered").register(registry);
        Gauge.builder("ledgercore.outbox.dead", this, m -> m.count("status = 'DEAD'"))
                .description("Dead-lettered outbox events needing operator attention").register(registry);
    }

    double count(String where) {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE " + where, Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
