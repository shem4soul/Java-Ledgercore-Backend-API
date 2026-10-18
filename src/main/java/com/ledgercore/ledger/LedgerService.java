package com.ledgercore.ledger;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.common.BusinessException;
import com.ledgercore.outbox.OutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place money moves. Guarantees, all inside ONE database transaction:
 *  1. both accounts are row-locked in a fixed (id) order, so concurrent transfers serialise and never deadlock;
 *  2. every rule (status, currency, funds, daily limit) is checked against the locked, current balances;
 *  3. exactly one DEBIT and one CREDIT of equal amount are written, with the resulting balances;
 *  4. the outbox event is written atomically with the change.
 * Any exception rolls the whole thing back; the database additionally enforces balance, no-overdraft and append-only rules.
 */
@Service
public class LedgerService {
    private final AccountRepository accounts;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    private final LedgerHooks hooks;
    private final MeterRegistry metrics;
    private final Clock clock;

    public LedgerService(AccountRepository accounts, EntityManager em, JdbcTemplate jdbc, OutboxService outbox,
                         LedgerHooks hooks, MeterRegistry metrics, Clock clock) {
        this.accounts = accounts;
        this.em = em;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.hooks = hooks;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** MANDATORY: callers must already be in a transaction (the idempotency wrapper or an admin service). */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult post(PostCommand c) {
        if (c.amountMinor() <= 0) throw BusinessException.badRequest("INVALID_AMOUNT", "Amount must be positive");
        if (c.debitAccountId().equals(c.creditAccountId())) throw BusinessException.unprocessable("SAME_ACCOUNT", "Cannot transfer to the same account");

        jdbc.execute("SET LOCAL lock_timeout = '5s'");   // fail fast instead of queueing forever behind a stuck transaction

        List<Account> locked = accounts.lockAllByIdOrdered(List.of(c.debitAccountId(), c.creditAccountId()));
        Account debit = pick(locked, c.debitAccountId());
        Account credit = pick(locked, c.creditAccountId());

        if (!debit.getCurrency().equals(c.currency()) || !credit.getCurrency().equals(c.currency())) {
            throw BusinessException.unprocessable("CURRENCY_MISMATCH", "Both accounts must be in " + c.currency());
        }
        if (!c.allowFrozenAccounts() && (!debit.isActive() || !credit.isActive())) {
            throw BusinessException.unprocessable("ACCOUNT_NOT_ACTIVE", "One of the accounts is not active");
        }
        if (c.enforceDailyLimit()) enforceDailyLimit(debit, c.amountMinor());

        debit.debit(c.amountMinor());                   // throws INSUFFICIENT_FUNDS for customer accounts
        hooks.afterDebit();                             // test seam: simulate a crash between the two legs
        credit.credit(c.amountMinor());

        Instant now = clock.instant();
        LedgerTransaction tx = new LedgerTransaction(c.type(), c.amountMinor(), c.currency(), c.description(),
                c.createdBy(), c.reversalOf(), now);
        em.persist(tx);
        em.persist(new LedgerEntry(tx.getId(), debit.getId(), EntryDirection.DEBIT, c.amountMinor(), debit.getBalanceMinor(), now));
        em.persist(new LedgerEntry(tx.getId(), credit.getId(), EntryDirection.CREDIT, c.amountMinor(), credit.getBalanceMinor(), now));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", tx.getId());
        payload.put("reference", tx.getReference());
        payload.put("transactionType", c.type().name());
        payload.put("amountMinor", c.amountMinor());
        payload.put("currency", c.currency());
        payload.put("debitAccountNumber", debit.getAccountNumber());
        payload.put("creditAccountNumber", credit.getAccountNumber());
        payload.put("debitOwnerId", debit.getOwnerId());
        payload.put("creditOwnerId", credit.getOwnerId());
        outbox.enqueue("LEDGER_TRANSACTION", tx.getId(), "LedgerTransactionPosted", payload);

        metrics.counter("ledgercore.transactions.posted", "type", c.type().name()).increment();
        return new PostResult(tx, debit.getAccountNumber(), credit.getAccountNumber(), debit.getBalanceMinor(), credit.getBalanceMinor());
    }

    /** Runs under the debit account's row lock, so concurrent transfers from one account cannot both squeeze under the limit. */
    private void enforceDailyLimit(Account debit, long amountMinor) {
        Instant dayStart = LocalDate.now(clock).atStartOfDay().toInstant(ZoneOffset.UTC);
        Long spent = jdbc.queryForObject(
                "SELECT COALESCE(SUM(e.amount_minor), 0) FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id "
                        + "WHERE e.account_id = ? AND e.direction = 'DEBIT' AND t.type = 'TRANSFER' AND e.created_at >= ?",
                Long.class, debit.getId(), java.sql.Timestamp.from(dayStart));
        long already = spent == null ? 0 : spent;
        if (already + amountMinor > debit.getDailyLimitMinor()) {
            throw BusinessException.unprocessable("DAILY_LIMIT_EXCEEDED", "Daily transfer limit exceeded");
        }
    }

    private static Account pick(List<Account> locked, UUID id) {
        return locked.stream().filter(a -> a.getId().equals(id)).findFirst()
                .orElseThrow(() -> BusinessException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
    }
}
