package com.ledgercore.ledger;

public record PostResult(LedgerTransaction transaction, String debitAccountNumber, String creditAccountNumber,
                         long debitBalanceAfter, long creditBalanceAfter) {}
