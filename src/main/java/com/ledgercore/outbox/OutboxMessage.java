package com.ledgercore.outbox;

import java.util.UUID;

public record OutboxMessage(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload, int attempts) {}
