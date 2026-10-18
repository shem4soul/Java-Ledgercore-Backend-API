package com.ledgercore.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgercore.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exactly-once semantics for money-moving requests.
 *
 * The key row, the business work and the stored response all commit in ONE transaction:
 *  - INSERT ... ON CONFLICT DO NOTHING on the (user, key) primary key. If another transaction is still working on the same
 *    key, PostgreSQL makes this INSERT wait until that transaction commits or rolls back, so duplicates can never run in parallel.
 *  - Winner: runs the action, stores the response, commits.
 *  - Loser: after the winner commits it sees the stored response and replays it (no second debit).
 *  - If the winner fails and rolls back, its key row disappears with it, so a retry runs from scratch (safe, nothing was applied).
 *  - Same key with a different payload is rejected (422) because it indicates a client bug.
 */
@Service
public class IdempotencyService {
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{8,80}$");

    public record Outcome<T>(T body, boolean replayed) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public <T> Outcome<T> execute(UUID userId, String key, String scope, String requestHash, Class<T> responseType, Supplier<T> action) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw BusinessException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-80 characters: letters, digits, . _ : -");
        }
        int inserted = jdbc.update(
                "INSERT INTO idempotency_keys (user_id, idem_key, scope, request_hash, status) VALUES (?, ?, ?, ?, 'IN_PROGRESS') "
                        + "ON CONFLICT (user_id, idem_key) DO NOTHING",
                userId, key, scope, requestHash);

        if (inserted == 1) {
            T result = action.get();
            jdbc.update("UPDATE idempotency_keys SET status = 'COMPLETED', response_body = ?::jsonb, completed_at = now() "
                    + "WHERE user_id = ? AND idem_key = ?", toJson(result), userId, key);
            return new Outcome<>(result, false);
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, request_hash, response_body::text AS body FROM idempotency_keys WHERE user_id = ? AND idem_key = ?",
                userId, key);
        if (rows.isEmpty()) {
            throw BusinessException.conflict("IDEMPOTENCY_RETRY", "Please retry the request");
        }
        Map<String, Object> row = rows.get(0);
        if (!requestHash.equals(row.get("request_hash"))) {
            throw BusinessException.unprocessable("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used with a different request");
        }
        if (!"COMPLETED".equals(row.get("status")) || row.get("body") == null) {
            throw BusinessException.conflict("REQUEST_IN_PROGRESS", "A request with this Idempotency-Key is still being processed");
        }
        try {
            return new Outcome<>(mapper.readValue((String) row.get("body"), responseType), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }

    public static String hash(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise response", e);
        }
    }
}
