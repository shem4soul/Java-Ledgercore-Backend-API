package com.ledgercore.account;

import com.ledgercore.common.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private UUID id;

    @Column(name = "account_number", nullable = false, updatable = false)
    private String accountNumber;

    @Column(name = "owner_id", updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountType type;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "daily_limit_minor", nullable = false)
    private long dailyLimitMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private Long version;

    protected Account() {}

    public Account(String accountNumber, UUID ownerId, AccountType type, String currency, long dailyLimitMinor) {
        this.id = UUID.randomUUID();
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.type = type;
        this.currency = currency;
        this.dailyLimitMinor = dailyLimitMinor;
    }

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    /** Customer accounts can never be overdrawn; system accounts may go negative (funds issued). */
    public void debit(long amountMinor) {
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be positive");
        if (type == AccountType.CUSTOMER && balanceMinor < amountMinor) {
            throw BusinessException.unprocessable("INSUFFICIENT_FUNDS", "Insufficient funds");
        }
        balanceMinor = Math.subtractExact(balanceMinor, amountMinor);
    }

    public void credit(long amountMinor) {
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be positive");
        balanceMinor = Math.addExact(balanceMinor, amountMinor);
    }

    public boolean isActive() { return status == AccountStatus.ACTIVE; }

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public UUID getOwnerId() { return ownerId; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public long getBalanceMinor() { return balanceMinor; }
    public long getDailyLimitMinor() { return dailyLimitMinor; }
    public void setDailyLimitMinor(long dailyLimitMinor) { this.dailyLimitMinor = dailyLimitMinor; }
    public Instant getCreatedAt() { return createdAt; }
}
