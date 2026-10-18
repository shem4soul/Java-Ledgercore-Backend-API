package com.ledgercore.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ledgercore")
public record AppProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Security security,
        @Valid @NotNull Ledger ledger,
        @Valid @NotNull Outbox outbox,
        Bootstrap bootstrap) {

    public record Jwt(
            @NotBlank @Size(min = 32, message = "JWT secret must be at least 32 characters") String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTtl,
            @NotNull Duration refreshTtl) {}

    public record Security(
            List<String> corsOrigins,
            @Min(1) int authRequestsPerMinute,
            @Min(1) int maxFailedLogins,
            @NotNull Duration lockDuration,
            @Min(4) int bcryptStrength) {}

    public record Ledger(@Min(0) long defaultDailyLimitMinor, @Min(1) int maxAccountsPerUser) {}

    public record Outbox(
            boolean enabled,
            @Min(100) long pollIntervalMs,
            @Min(1) int batchSize,
            @Min(1) int maxAttempts,
            @NotNull Duration baseBackoff,
            @NotNull Duration maxBackoff,
            @NotNull Duration lease) {}

    public record Bootstrap(String adminEmail, String adminPassword) {}
}
