package com.ledgercore.notification;

import com.ledgercore.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    public record NotificationView(UUID id, String kind, String message, Instant createdAt) {}

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<NotificationView> mine(@AuthenticationPrincipal AuthenticatedUser user,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        int s = Math.min(Math.max(size, 1), 100);
        return jdbc.query("SELECT id, kind, message, created_at FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> new NotificationView(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("message"),
                        rs.getTimestamp("created_at").toInstant()),
                user.id(), s, (long) Math.max(page, 0) * s);
    }
}
