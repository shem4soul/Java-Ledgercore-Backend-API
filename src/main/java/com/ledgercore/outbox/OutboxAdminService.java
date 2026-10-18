package com.ledgercore.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgercore.audit.AuditService;
import com.ledgercore.common.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxAdminService {
    public record OutboxView(UUID id, String eventType, String status, int attempts, Instant nextAttemptAt,
                             String lastError, Instant createdAt, JsonNode payload) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public OutboxAdminService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<OutboxView> list(String status, int page, int size) {
        String st = (status == null || status.isBlank()) ? null : status.toUpperCase();
        return jdbc.query(
                "SELECT id, event_type, status, attempts, next_attempt_at, last_error, created_at, payload::text AS payload "
                        + "FROM outbox_events WHERE (?::text IS NULL OR status = ?) ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    JsonNode payload = null;
                    try {
                        payload = mapper.readTree(rs.getString("payload"));
                    } catch (JsonProcessingException ignored) {
                        // leave null
                    }
                    return new OutboxView(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("status"),
                            rs.getInt("attempts"), rs.getTimestamp("next_attempt_at").toInstant(), rs.getString("last_error"),
                            rs.getTimestamp("created_at").toInstant(), payload);
                }, st, st, size, (long) page * size);
    }

    /** Operator replay of a dead/failed event. The inbox guarantees handlers that already succeeded do not repeat their effect. */
    @Transactional
    public void retry(UUID actorId, UUID eventId) {
        int n = jdbc.update("UPDATE outbox_events SET status = 'PENDING', attempts = 0, next_attempt_at = now(), locked_until = NULL, last_error = NULL "
                + "WHERE id = ? AND status IN ('DEAD', 'FAILED')", eventId);
        if (n == 0) throw BusinessException.conflict("NOT_RETRYABLE", "Event not found or not in a retryable state");
        audit.record(actorId, "OUTBOX_EVENT_REQUEUED", "OUTBOX_EVENT", eventId.toString(), Map.of());
    }
}
