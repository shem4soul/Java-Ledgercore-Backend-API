package com.ledgercore.account;

import com.ledgercore.account.AccountDtos.BeneficiaryResponse;
import com.ledgercore.audit.AuditService;
import com.ledgercore.common.BusinessException;
import com.ledgercore.common.Money;
import com.ledgercore.common.PageResult;
import com.ledgercore.config.AppProperties;
import com.ledgercore.security.AuthenticatedUser;
import com.ledgercore.security.Permission;
import com.ledgercore.user.User;
import com.ledgercore.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AppProperties props;

    public AccountService(AccountRepository accounts, UserRepository users, JdbcTemplate jdbc, AuditService audit, AppProperties props) {
        this.accounts = accounts;
        this.users = users;
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
    }

    @Transactional
    public Account open(UUID userId, String currency) {
        if (!Money.isSupported(currency)) {
            throw BusinessException.badRequest("UNSUPPORTED_CURRENCY", "Supported currencies: " + Money.SUPPORTED_CURRENCIES);
        }
        // serialise per user so two parallel requests cannot both slip past the per-user cap
        users.findById(userId).orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "User not found"));
        long lockKey = userId.getMostSignificantBits() ^ userId.getLeastSignificantBits(); // numeric, so safe to inline
        jdbc.execute("SELECT pg_advisory_xact_lock(" + lockKey + ")");
        if (accounts.countByOwnerIdAndType(userId, AccountType.CUSTOMER) >= props.ledger().maxAccountsPerUser()) {
            throw BusinessException.conflict("ACCOUNT_LIMIT_REACHED", "Maximum number of accounts reached");
        }
        String number = jdbc.queryForObject("SELECT lpad(nextval('account_number_seq')::text, 10, '0')", String.class);
        Account account = accounts.save(new Account(number, userId, AccountType.CUSTOMER, currency, props.ledger().defaultDailyLimitMinor()));
        audit.record(userId, "ACCOUNT_OPENED", "ACCOUNT", account.getId().toString(), Map.of("currency", currency, "accountNumber", number));
        return account;
    }

    @Transactional(readOnly = true)
    public List<Account> listMine(UUID userId) {
        return accounts.findByOwnerIdOrderByCreatedAtAsc(userId);
    }

    /** Owners see their own accounts; staff with ACCOUNT_READ_ANY see all. Anyone else gets 404 (no existence leak). */
    @Transactional(readOnly = true)
    public Account getVisible(AuthenticatedUser viewer, UUID accountId) {
        Account a = accounts.findById(accountId).orElseThrow(AccountService::notFound);
        boolean owner = viewer.id().equals(a.getOwnerId());
        if (!owner && !viewer.can(Permission.ACCOUNT_READ_ANY)) throw notFound();
        return a;
    }

    @Transactional(readOnly = true)
    public BeneficiaryResponse lookup(String accountNumber) {
        Account a = accounts.findByAccountNumber(accountNumber)
                .filter(x -> x.getType() == AccountType.CUSTOMER)
                .orElseThrow(() -> BusinessException.notFound("BENEFICIARY_NOT_FOUND", "No account found with that number"));
        String name = users.findById(a.getOwnerId()).map(User::getFullName).map(AccountService::mask).orElse("Account holder");
        return new BeneficiaryResponse(a.getAccountNumber(), a.getCurrency(), name);
    }

    @Transactional(readOnly = true)
    public PageResult<Account> adminList(AccountStatus status, int page, int size) {
        PageRequest pr = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = status == null ? accounts.findAll(pr) : accounts.findByStatus(status, pr);
        return new PageResult<>(result.getContent(), page, size, result.getTotalElements());
    }

    @Transactional
    public Account setStatus(UUID actorId, UUID accountId, AccountStatus status, String reason) {
        Account a = lockOne(accountId);
        if (a.getType() == AccountType.SYSTEM) throw BusinessException.forbidden("SYSTEM_ACCOUNT", "System accounts cannot be changed");
        AccountStatus before = a.getStatus();
        a.setStatus(status);
        audit.record(actorId, status == AccountStatus.FROZEN ? "ACCOUNT_FROZEN" : "ACCOUNT_STATUS_CHANGED", "ACCOUNT", accountId.toString(),
                Map.of("from", before.name(), "to", status.name(), "reason", reason == null ? "" : reason));
        return a;
    }

    @Transactional
    public Account setDailyLimit(UUID actorId, UUID accountId, long limitMinor) {
        Account a = lockOne(accountId);
        long before = a.getDailyLimitMinor();
        a.setDailyLimitMinor(limitMinor);
        audit.record(actorId, "ACCOUNT_LIMIT_CHANGED", "ACCOUNT", accountId.toString(), Map.of("from", before, "to", limitMinor));
        return a;
    }

    private Account lockOne(UUID id) {
        List<Account> locked = accounts.lockAllByIdOrdered(List.of(id));
        if (locked.isEmpty()) throw notFound();
        return locked.get(0);
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("ACCOUNT_NOT_FOUND", "Account not found");
    }

    /** "Adaeze Okafor" -> "A***** O*****" */
    static String mask(String fullName) {
        StringBuilder sb = new StringBuilder();
        for (String part : fullName.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.charAt(0)).append("*".repeat(Math.min(5, Math.max(1, part.length() - 1))));
        }
        return sb.toString();
    }
}
