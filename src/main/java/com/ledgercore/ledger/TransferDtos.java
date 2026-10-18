package com.ledgercore.ledger;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {
    private TransferDtos() {}

    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotBlank @Pattern(regexp = "\\d{10}", message = "toAccountNumber must be 10 digits") String toAccountNumber,
            @Min(value = 1, message = "amountMinor must be at least 1") @Max(value = 100_000_000_000L, message = "amountMinor too large") long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code") String currency,
            @Size(max = 140) String description) {}

    public record TransferResponse(UUID transactionId, String reference, String type, String status, long amountMinor,
                                   String currency, UUID fromAccountId, String toAccountNumber,
                                   long senderBalanceAfterMinor, Instant createdAt) {}

    public record AdjustmentRequest(
            @Min(1) @Max(100_000_000_000L) long amountMinor,
            @NotBlank @Size(max = 140) String description) {}

    public record ReverseRequest(@NotBlank @Size(max = 140) String reason) {}
}
