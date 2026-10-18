package com.ledgercore.ledger;

import java.util.UUID;

/** A request to move money between two accounts as one balanced ledger transaction. */
public record PostCommand(
        TransactionType type,
        UUID debitAccountId,
        UUID creditAccountId,
        long amountMinor,
        String currency,
        String description,
        UUID createdBy,
        UUID reversalOf,
        boolean enforceDailyLimit,
        boolean allowFrozenAccounts) {}
