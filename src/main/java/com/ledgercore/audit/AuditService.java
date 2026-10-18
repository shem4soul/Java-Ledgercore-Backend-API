package com.ledgercore.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Append-only audit trail (the table rejects UPDATE/DELETE/TRUNCATE at the database level). */
@Service
public class AuditService {
    public record AuditView(UUID id, UUID actorId, String action, String entityType, String entityId,
                            JsonNode details, String ipAddress, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Joins the caller's transaction: the audit row commits or rolls back together with the business change. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID actorId, String action, String entityType, String entityId, Map<String, Object> details) {
        insert(actorId, action, entityType, entityId, details);
    }

    /** Own transaction: survives a rollback of the caller (e.g. failed logins, denied operations). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(UUID actorId, String action, String entityType, String entityId, Map<String, Object> details) {
        insert(actorId, action, entityType, entityId, details);
    }

    private void insert(UUID actorId, String action, String entityType, String entityId, Map<String, Object> details) {
        String json;
        try {
            json = mapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        jdbc.update("INSERT INTO audit_logs (id, actor_id, action, entity_type, entity_id, details, ip_address) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
                UUID.randomUUID(), actorId, action, entityType, entityId, json, currentIp());
    }

    @Transactional(readOnly = true)
    public List<AuditView> search(String action, UUID actorId, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, actor_id, action, entity_type, entity_id, details::text AS details, ip_address, created_at FROM audit_logs WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) { sql.append(" AND action = ?"); args.add(action); }
        if (actorId != null) { sql.append(" AND actor_id = ?"); args.add(actorId); }
        sql.append(" ORDER BY created_at DESC, id LIMIT ? OFFSET ?");
        args.add(size);
        args.add((long) page * size);
        return jdbc.query(sql.toString(), (rs, i) -> {
            JsonNode details = null;
            try {
                String d = rs.getString("details");
                details = d == null ? null : mapper.readTree(d);
            } catch (JsonProcessingException ignored) {
                // leave null
            }
            return new AuditView(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class), rs.getString("action"),
                    rs.getString("entity_type"), rs.getString("entity_id"), details, rs.getString("ip_address"),
                    rs.getTimestamp("created_at").toInstant());
        }, args.toArray());
    }

    private static String currentIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest().getRemoteAddr();
        return null;
    }
}
