package com.ledgercore.notification;

import java.util.UUID;

/** Delivery channel (email/SMS/push). Swap the implementation (e.g. SES/SNS) without touching the domain code. */
public interface NotificationSender {
    void send(UUID userId, String subject, String message) throws Exception;
}
