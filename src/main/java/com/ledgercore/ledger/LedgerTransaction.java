package com.ledgercore.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {
    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status = TransactionStatus.POSTED;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(length = 255, updatable = false)
    private String description;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reversal_of", updatable = false)
    private UUID reversalOf;

    @Version
    private Long version;

    protected LedgerTransaction() {}

    public LedgerTransaction(TransactionType type, long amountMinor, String currency, String description,
                             UUID createdBy, UUID reversalOf, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.reference = "TXN-" + id.toString().replace("-", "").substring(0, 20).toUpperCase();
        this.type = type;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.description = description;
        this.createdBy = createdBy;
        this.reversalOf = reversalOf;
        this.createdAt = createdAt;
    }

    public void markReversed() { this.status = TransactionStatus.REVERSED; }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public TransactionType getType() { return type; }
    public TransactionStatus getStatus() { return status; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getReversalOf() { return reversalOf; }
}
