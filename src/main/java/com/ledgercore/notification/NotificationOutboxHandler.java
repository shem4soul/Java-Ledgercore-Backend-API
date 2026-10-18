package com.ledgercore.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgercore.common.Money;
import com.ledgercore.outbox.OutboxHandler;
import com.ledgercore.outbox.OutboxMessage;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns "LedgerTransactionPosted" events into in-app notifications and calls the sender.
 * The notification row is unique per (event, user, kind), and the publisher's inbox guarantees the handler body runs once
 * per event; if the sender throws, the whole unit of work rolls back and the event is retried with backoff.
 */
@Component
public class NotificationOutboxHandler implements OutboxHandler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NotificationSender sender;

    public NotificationOutboxHandler(JdbcTemplate jdbc, ObjectMapper mapper, NotificationSender sender) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.sender = sender;
    }

    @Override
    public String name() { return "notification"; }

    @Override
    public boolean supports(String eventType) { return "LedgerTransactionPosted".equals(eventType); }

    @Override
    public void handle(OutboxMessage message) throws Exception {
        JsonNode p = mapper.readTree(message.payload());
        String type = p.get("transactionType").asText();
        String amount = Money.format(p.get("amountMinor").asLong(), p.get("currency").asText());
        String ref = p.get("reference").asText();

        notifyUser(message.id(), p.get("debitOwnerId"), "DEBIT_ALERT", switch (type) {
            case "TRANSFER" -> "You sent " + amount + " to account " + p.get("creditAccountNumber").asText() + " (" + ref + ")";
            case "WITHDRAWAL" -> "A withdrawal of " + amount + " was made from your account (" + ref + ")";
            case "REVERSAL" -> amount + " was reversed out of your account (" + ref + ")";
            default -> amount + " was debited from your account (" + ref + ")";
        });
        notifyUser(message.id(), p.get("creditOwnerId"), "CREDIT_ALERT", switch (type) {
            case "TRANSFER" -> "You received " + amount + " from account " + p.get("debitAccountNumber").asText() + " (" + ref + ")";
            case "DEPOSIT" -> "A deposit of " + amount + " was credited to your account (" + ref + ")";
            case "REVERSAL" -> amount + " was returned to your account (" + ref + ")";
            default -> amount + " was credited to your account (" + ref + ")";
        });
    }

    private void notifyUser(UUID eventId, JsonNode ownerId, String kind, String text) throws Exception {
        if (ownerId == null || ownerId.isNull()) return;          // SYSTEM accounts have no owner to notify
        UUID userId = UUID.fromString(ownerId.asText());
        jdbc.update("INSERT INTO notifications (id, user_id, event_id, kind, message) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (event_id, user_id, kind) DO NOTHING", UUID.randomUUID(), userId, eventId, kind, text);
        sender.send(userId, kind, text);
    }
}
