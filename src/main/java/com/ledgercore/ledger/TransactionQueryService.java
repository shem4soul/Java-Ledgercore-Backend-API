package com.ledgercore.ledger;

import com.ledgercore.common.BusinessException;
import com.ledgercore.common.PageResult;
import com.ledgercore.security.AuthenticatedUser;
import com.ledgercore.security.Permission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionQueryService {
    public record HistoryItem(UUID transactionId, String reference, String type, String status, String description,
                              String direction, long amountMinor, long balanceAfterMinor, String currency,
                              String counterpartyAccountNumber, Instant createdAt) {}

    public record TransactionView(UUID id, String reference, String type, String status, long amountMinor, String currency,
                                  String description, UUID reversalOf, Instant createdAt, List<EntryView> entries) {}

    public record EntryView(String accountNumber, String direction, long amountMinor, long balanceAfterMinor) {}

    private final JdbcTemplate jdbc;

    public TransactionQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Statement for one account: newest first, with the counterparty account number. */
    @Transactional(readOnly = true)
    public PageResult<HistoryItem> history(UUID accountId, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int p = Math.max(page, 0);
        List<HistoryItem> items = jdbc.query(
                "SELECT t.id, t.reference, t.type, t.status, t.description, t.currency, e.direction, e.amount_minor, e.balance_after, e.created_at, "
                        + " (SELECT a2.account_number FROM ledger_entries e2 JOIN accounts a2 ON a2.id = e2.account_id "
                        + "   WHERE e2.transaction_id = t.id AND e2.account_id <> e.account_id LIMIT 1) AS counterparty "
                        + "FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id "
                        + "WHERE e.account_id = ? ORDER BY e.created_at DESC, e.id LIMIT ? OFFSET ?",
                (rs, i) -> new HistoryItem(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("type"),
                        rs.getString("status"), rs.getString("description"), rs.getString("direction"), rs.getLong("amount_minor"),
                        rs.getLong("balance_after"), rs.getString("currency"), rs.getString("counterparty"),
                        rs.getTimestamp("created_at").toInstant()),
                accountId, s, (long) p * s);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE account_id = ?", Long.class, accountId);
        return new PageResult<>(items, p, s, total == null ? 0 : total);
    }

    /** A transaction is visible to a participant (owner of either account) or to staff with TRANSACTION_READ_ANY. */
    @Transactional(readOnly = true)
    public TransactionView get(AuthenticatedUser viewer, UUID transactionId) {
        List<TransactionView> head = jdbc.query(
                "SELECT id, reference, type, status, amount_minor, currency, description, reversal_of, created_at FROM ledger_transactions WHERE id = ?",
                (rs, i) -> new TransactionView(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("type"),
                        rs.getString("status"), rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("description"),
                        rs.getObject("reversal_of", UUID.class), rs.getTimestamp("created_at").toInstant(), List.of()),
                transactionId);
        if (head.isEmpty()) throw notFound();
        if (!viewer.can(Permission.TRANSACTION_READ_ANY)) {
            Integer mine = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_entries e JOIN accounts a ON a.id = e.account_id WHERE e.transaction_id = ? AND a.owner_id = ?",
                    Integer.class, transactionId, viewer.id());
            if (mine == null || mine == 0) throw notFound();
        }
        List<EntryView> entries = jdbc.query(
                "SELECT a.account_number, e.direction, e.amount_minor, e.balance_after FROM ledger_entries e "
                        + "JOIN accounts a ON a.id = e.account_id WHERE e.transaction_id = ? ORDER BY e.direction",
                (rs, i) -> new EntryView(rs.getString("account_number"), rs.getString("direction"), rs.getLong("amount_minor"), rs.getLong("balance_after")),
                transactionId);
        TransactionView h = head.get(0);
        return new TransactionView(h.id(), h.reference(), h.type(), h.status(), h.amountMinor(), h.currency(), h.description(),
                h.reversalOf(), h.createdAt(), entries);
    }

    @Transactional(readOnly = true)
    public PageResult<TransactionView> adminSearch(String type, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int p = Math.max(page, 0);
        String t = (type == null || type.isBlank()) ? null : type.toUpperCase();
        List<TransactionView> items = jdbc.query(
                "SELECT id, reference, type, status, amount_minor, currency, description, reversal_of, created_at FROM ledger_transactions "
                        + "WHERE (?::text IS NULL OR type = ?) ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> new TransactionView(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("type"),
                        rs.getString("status"), rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("description"),
                        rs.getObject("reversal_of", UUID.class), rs.getTimestamp("created_at").toInstant(), List.of()),
                t, t, s, (long) p * s);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions WHERE (?::text IS NULL OR type = ?)", Long.class, t, t);
        return new PageResult<>(items, p, s, total == null ? 0 : total);
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("TRANSACTION_NOT_FOUND", "Transaction not found");
    }
}
