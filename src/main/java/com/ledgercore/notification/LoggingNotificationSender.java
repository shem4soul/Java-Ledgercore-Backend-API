package com.ledgercore.notification;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Default sender: logs instead of sending. Replace with an SES/SNS-backed bean in real deployments. */
@Component
public class LoggingNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(UUID userId, String subject, String message) {
        log.info("NOTIFY user={} subject='{}' message='{}'", userId, subject, message);
    }
}
