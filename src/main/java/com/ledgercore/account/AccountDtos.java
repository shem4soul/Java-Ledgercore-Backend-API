package com.ledgercore.account;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

public final class AccountDtos {
    private AccountDtos() {}

    public record OpenAccountRequest(@NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code") String currency) {}

    public record LimitRequest(@Min(0) long dailyLimitMinor) {}

    public record AccountResponse(UUID id, String accountNumber, UUID ownerId, String currency, AccountType type,
                                  AccountStatus status, long balanceMinor, long dailyLimitMinor, Instant createdAt) {
        public static AccountResponse from(Account a) {
            return new AccountResponse(a.getId(), a.getAccountNumber(), a.getOwnerId(), a.getCurrency(), a.getType(),
                    a.getStatus(), a.getBalanceMinor(), a.getDailyLimitMinor(), a.getCreatedAt());
        }
    }

    /** What a payer sees when confirming a beneficiary: enough to avoid mistakes, not enough to leak data. */
    public record BeneficiaryResponse(String accountNumber, String currency, String accountName) {}
}
