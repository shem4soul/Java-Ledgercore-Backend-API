package com.ledgercore.admin;

import com.ledgercore.audit.AuditService;
import com.ledgercore.common.BusinessException;
import com.ledgercore.common.PageResult;
import com.ledgercore.security.Role;
import com.ledgercore.user.User;
import com.ledgercore.user.UserDtos.UserResponse;
import com.ledgercore.user.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    private final UserRepository users;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public AdminService(UserRepository users, AuditService audit, JdbcTemplate jdbc) {
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResult<UserResponse> listUsers(int page, int size) {
        var result = users.findAll(PageRequest.of(Math.max(page, 0), size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResult<>(result.getContent().stream().map(UserResponse::from).toList(), page, size, result.getTotalElements());
    }

    @Transactional
    public UserResponse setEnabled(UUID actorId, UUID userId, boolean enabled) {
        if (actorId.equals(userId) && !enabled) throw BusinessException.unprocessable("CANNOT_DISABLE_SELF", "You cannot disable your own account");
        User u = users.findById(userId).orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "User not found"));
        u.setEnabled(enabled);
        if (enabled) u.resetLoginFailures();
        if (!enabled) jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId);
        audit.record(actorId, enabled ? "USER_ENABLED" : "USER_DISABLED", "USER", userId.toString(), Map.of());
        return UserResponse.from(u);
    }

    @Transactional
    public UserResponse setRoles(UUID actorId, UUID userId, Set<Role> roles) {
        if (actorId.equals(userId) && !roles.contains(Role.ADMIN)) {
            throw BusinessException.unprocessable("CANNOT_REMOVE_OWN_ADMIN", "You cannot remove your own ADMIN role");
        }
        User u = users.findById(userId).orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "User not found"));
        Set<Role> before = Set.copyOf(u.getRoles());
        u.setRoles(roles);
        audit.record(actorId, "USER_ROLES_CHANGED", "USER", userId.toString(), Map.of("from", before.toString(), "to", roles.toString()));
        return UserResponse.from(u);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class));
        m.put("customerAccounts", jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE type = 'CUSTOMER'", Long.class));
        m.put("frozenAccounts", jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE status = 'FROZEN'", Long.class));
        m.put("transactionsLast24h", jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE created_at > now() - interval '24 hours'", Long.class));
        m.put("transferVolumeLast24hByCurrency", jdbc.queryForList(
                "SELECT currency, COALESCE(SUM(amount_minor), 0) AS volume_minor, COUNT(*) AS count FROM ledger_transactions "
                        + "WHERE type = 'TRANSFER' AND created_at > now() - interval '24 hours' GROUP BY currency ORDER BY currency"));
        m.put("outboxPending", jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status IN ('PENDING','FAILED','PROCESSING')", Long.class));
        m.put("outboxDead", jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status = 'DEAD'", Long.class));
        return m;
    }
}
