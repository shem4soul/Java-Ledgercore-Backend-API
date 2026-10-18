package com.ledgercore.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox: events are written in the SAME database transaction as the business change, so an event exists
 * if and only if the change committed. A separate publisher delivers them with retries.
 */
@Service
public class OutboxService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?, ?::jsonb)",
                    id, aggregateType, aggregateId, eventType, mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise outbox payload", e);
        }
        return id;
    }
}
