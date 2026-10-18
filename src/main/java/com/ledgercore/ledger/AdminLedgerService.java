package com.ledgercore.ledger;

import com.ledgercore.account.AccountRef;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.audit.AuditService;
import com.ledgercore.common.BusinessException;
import com.ledgercore.ledger.TransferDtos.TransferResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Staff-initiated ledger operations: deposits, withdrawals and reversals. Always audited. */
@Service
public class AdminLedgerService {
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final AuditService audit;

    public AdminLedgerService(AccountRepository accounts, LedgerService ledger, LedgerTransactionRepository transactions,
                              LedgerEntryRepository entries, AuditService audit) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.transactions = transactions;
        this.entries = entries;
        this.audit = audit;
    }

    /** Debits the currency's SYSTEM cash account, credits the customer. Call inside an idempotent transaction. */
    @Transactional
    public TransferResponse deposit(UUID adminId, UUID accountId, long amountMinor, String description) {
        AccountRef target = customerAccount(accountId);
        AccountRef cash = systemAccount(target.getCurrency());
        PostResult r = ledger.post(new PostCommand(TransactionType.DEPOSIT, cash.getId(), target.getId(), amountMinor,
                target.getCurrency(), description, adminId, null, false, false));
        audit.record(adminId, "DEPOSIT_POSTED", "LEDGER_TRANSACTION", r.transaction().getId().toString(),
                Map.of("accountId", accountId.toString(), "amountMinor", amountMinor, "reference", r.transaction().getReference()));
        return response(r, TransactionType.DEPOSIT, target.getId(), target.getAccountNumber(), r.creditBalanceAfter());
    }

    /** Debits the customer, credits the SYSTEM cash account. Fails with INSUFFICIENT_FUNDS if the balance is too low. */
    @Transactional
    public TransferResponse withdraw(UUID adminId, UUID accountId, long amountMinor, String description) {
        AccountRef source = customerAccount(accountId);
        AccountRef cash = systemAccount(source.getCurrency());
        PostResult r = ledger.post(new PostCommand(TransactionType.WITHDRAWAL, source.getId(), cash.getId(), amountMinor,
                source.getCurrency(), description, adminId, null, false, false));
        audit.record(adminId, "WITHDRAWAL_POSTED", "LEDGER_TRANSACTION", r.transaction().getId().toString(),
                Map.of("accountId", accountId.toString(), "amountMinor", amountMinor, "reference", r.transaction().getReference()));
        return response(r, TransactionType.WITHDRAWAL, source.getId(), cash.getAccountNumber(), r.debitBalanceAfter());
    }

    /**
     * Reverses a posted transaction by writing a compensating transaction (the ledger is never edited). The original is
     * row-locked first, so two admins reversing the same transaction cannot both succeed; reversal_of is also UNIQUE.
     */
    @Transactional
    public TransferResponse reverse(UUID adminId, UUID transactionId, String reason) {
        LedgerTransaction original = transactions.findByIdForUpdate(transactionId)
                .orElseThrow(() -> BusinessException.notFound("TRANSACTION_NOT_FOUND", "Transaction not found"));
        if (original.getType() == TransactionType.REVERSAL) {
            throw BusinessException.unprocessable("CANNOT_REVERSE_REVERSAL", "A reversal cannot be reversed");
        }
        if (original.getStatus() != TransactionStatus.POSTED) {
            throw BusinessException.conflict("ALREADY_REVERSED", "Transaction was already reversed");
        }
        List<LedgerEntry> legs = entries.findByTransactionId(transactionId);
        UUID debited = legs.stream().filter(e -> e.getDirection() == EntryDirection.DEBIT).map(LedgerEntry::getAccountId).findFirst().orElseThrow();
        UUID credited = legs.stream().filter(e -> e.getDirection() == EntryDirection.CREDIT).map(LedgerEntry::getAccountId).findFirst().orElseThrow();

        // money flows back: the original credit account is debited (must still hold the funds), the original debit account credited
        PostResult r = ledger.post(new PostCommand(TransactionType.REVERSAL, credited, debited, original.getAmountMinor(),
                original.getCurrency(), "Reversal of " + original.getReference() + ": " + reason, adminId, original.getId(), false, true));
        original.markReversed();
        audit.record(adminId, "TRANSACTION_REVERSED", "LEDGER_TRANSACTION", transactionId.toString(),
                Map.of("reason", reason, "reversalReference", r.transaction().getReference()));
        return response(r, TransactionType.REVERSAL, credited, r.creditAccountNumber(), r.debitBalanceAfter());
    }

    private AccountRef customerAccount(UUID id) {
        return accounts.findRefById(id).filter(a -> a.getType() == AccountType.CUSTOMER)
                .orElseThrow(() -> BusinessException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
    }

    private AccountRef systemAccount(String currency) {
        return accounts.findRefByTypeAndCurrency(AccountType.SYSTEM, currency)
                .orElseThrow(() -> BusinessException.unprocessable("NO_SYSTEM_ACCOUNT", "No system account for " + currency));
    }

    private static TransferResponse response(PostResult r, TransactionType type, UUID accountId, String counterpartyNumber, long balanceAfter) {
        LedgerTransaction t = r.transaction();
        return new TransferResponse(t.getId(), t.getReference(), type.name(), t.getStatus().name(), t.getAmountMinor(),
                t.getCurrency(), accountId, counterpartyNumber, balanceAfter, t.getCreatedAt());
    }
}
