package com.ledgercore.outbox;

/**
 * Consumes outbox events. Implementations must be safe to retry: the publisher guarantees at-least-once delivery, and an
 * inbox row (processed_events) makes the handler's database effects happen at most once per event.
 */
public interface OutboxHandler {
    /** Stable unique name, used as the inbox key. */
    String name();

    boolean supports(String eventType);

    /** Throw to signal failure; the event is retried with exponential backoff and eventually dead-lettered. */
    void handle(OutboxMessage message) throws Exception;
}
