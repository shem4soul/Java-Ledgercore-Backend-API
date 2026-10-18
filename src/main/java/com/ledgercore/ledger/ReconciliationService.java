package com.ledgercore.ledger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Independent proof that the books are right. Runs three checks in ONE repeatable-read snapshot (so live traffic cannot
 * cause false alarms): stored balances equal the sum of ledger entries; the sum of all balances is zero (every transaction
 * is balanced); no ledger transaction is unbalanced.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public record Mismatch(UUID accountId, String accountNumber, long storedBalanceMinor, long ledgerBalanceMinor) {}

    public record Report(Instant ranAt, long accountsChecked, List<Mismatch> mismatches, long sumOfAllBalancesMinor,
                         long unbalancedTransactions, boolean healthy) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final AtomicLong discrepancies = new AtomicLong();

    public ReconciliationService(JdbcTemplate jdbc, PlatformTransactionManager txManager, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.snapshot = new TransactionTemplate(txManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        Gauge.builder("ledgercore.reconciliation.discrepancies", discrepancies, AtomicLong::get)
                .description("Discrepancies found by the last reconciliation run (0 = healthy)").register(metrics);
    }

    public Report run() {
        return execute(false);
    }

    /**
     * @param exclusive when true, a transaction-scoped advisory lock makes only one instance run at a time; returns null if
     *                  another instance holds it. (Transaction-scoped, so it can never leak across pooled connections.)
     */
    private Report execute(boolean exclusive) {
        Report report = snapshot.execute(status -> {
            if (exclusive && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(7001)", Boolean.class))) {
                return null;
            }
            Long accounts = jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Long.class);
            List<Mismatch> mismatches = jdbc.query(
                    "SELECT a.id, a.account_number, a.balance_minor AS stored, "
                            + " COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0) AS ledger "
                            + "FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id "
                            + "GROUP BY a.id, a.account_number, a.balance_minor "
                            + "HAVING a.balance_minor <> COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)",
                    (rs, i) -> new Mismatch(rs.getObject("id", UUID.class), rs.getString("account_number"), rs.getLong("stored"), rs.getLong("ledger")));
            Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(balance_minor), 0) FROM accounts", Long.class);
            Long unbalanced = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT transaction_id FROM ledger_entries GROUP BY transaction_id "
                            + "HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0) x", Long.class);
            long s = sum == null ? 0 : sum;
            long u = unbalanced == null ? 0 : unbalanced;
            boolean healthy = mismatches.isEmpty() && s == 0 && u == 0;
            return new Report(Instant.now(), accounts == null ? 0 : accounts, mismatches, s, u, healthy);
        });
        if (report == null) return null;
        long found = report.mismatches().size() + report.unbalancedTransactions() + (report.sumOfAllBalancesMinor() != 0 ? 1 : 0);
        discrepancies.set(found);
        if (!report.healthy()) log.error("RECONCILIATION FAILED: {} mismatching accounts, global sum {}, {} unbalanced transactions",
                report.mismatches().size(), report.sumOfAllBalancesMinor(), report.unbalancedTransactions());
        return report;
    }

    /** Nightly run. An advisory lock makes sure only one instance does the work when several are running. */
    @Scheduled(cron = "${ledgercore.reconciliation.cron:0 0 2 * * *}")
    public void nightly() {
        Report r = execute(true);
        if (r != null) log.info("Nightly reconciliation: healthy={} accounts={}", r.healthy(), r.accountsChecked());
    }
}
