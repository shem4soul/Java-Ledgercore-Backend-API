package com.ledgercore.user;

import com.ledgercore.audit.AuditService;
import com.ledgercore.common.BusinessException;
import com.ledgercore.config.AppProperties;
import com.ledgercore.security.JwtService;
import com.ledgercore.security.Role;
import com.ledgercore.user.UserDtos.LoginRequest;
import com.ledgercore.user.UserDtos.RegisterRequest;
import com.ledgercore.user.UserDtos.TokenResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, JdbcTemplate jdbc,
                       AuditService audit, AppProperties props, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.dummyHash = encoder.encode("not-a-real-password"); // used to equalise timing for unknown emails
    }

    @Transactional
    public User register(RegisterRequest req) {
        if (users.existsByEmailIgnoreCase(req.email().trim())) {
            throw BusinessException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = users.save(new User(req.email(), encoder.encode(req.password()), req.fullName(), EnumSet.of(Role.CUSTOMER)));
        audit.record(user.getId(), "USER_REGISTERED", "USER", user.getId().toString(), Map.of("email", user.getEmail()));
        return user;
    }

    /** noRollbackFor: the failed-attempt counter and lock must be persisted even though we throw. */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse login(LoginRequest req) {
        Optional<User> found = users.findByEmailIgnoreCase(req.email().trim());
        if (found.isEmpty()) {
            encoder.matches(req.password(), dummyHash);
            audit.recordIndependent(null, "LOGIN_FAILED", "USER", null, Map.of("reason", "unknown_email"));
            throw invalidCredentials();
        }
        User user = found.get();
        Instant now = clock.instant();
        if (user.isLocked(now)) {
            audit.recordIndependent(user.getId(), "LOGIN_BLOCKED", "USER", user.getId().toString(), Map.of("reason", "locked"));
            throw new BusinessException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "Account temporarily locked. Try again later.");
        }
        if (!user.isEnabled() || !encoder.matches(req.password(), user.getPasswordHash())) {
            if (user.isEnabled()) user.registerFailedLogin(props.security().maxFailedLogins(), props.security().lockDuration(), now);
            users.save(user);
            audit.recordIndependent(user.getId(), "LOGIN_FAILED", "USER", user.getId().toString(), Map.of("reason", "bad_credentials"));
            throw invalidCredentials();
        }
        user.resetLoginFailures();
        users.save(user);
        audit.record(user.getId(), "LOGIN_SUCCESS", "USER", user.getId().toString(), Map.of());
        return issueTokens(user, UUID.randomUUID());
    }

    /**
     * Refresh-token rotation with reuse detection: each token is single-use. Presenting an already-used token means it
     * was stolen or replayed, so the whole token family is revoked. noRollbackFor keeps that revocation.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse refresh(String rawToken) {
        String hash = sha256(rawToken);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, user_id, family_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = ?", hash);
        if (rows.isEmpty()) throw BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "Invalid refresh token");
        Map<String, Object> row = rows.get(0);
        UUID id = (UUID) row.get("id");
        UUID userId = (UUID) row.get("user_id");
        UUID family = (UUID) row.get("family_id");
        if (row.get("revoked_at") != null) {
            jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = ? AND revoked_at IS NULL", family);
            audit.recordIndependent(userId, "REFRESH_TOKEN_REUSE_DETECTED", "USER", userId.toString(), Map.of("familyId", family.toString()));
            throw BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "Invalid refresh token");
        }
        Instant expiresAt = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
        if (expiresAt.isBefore(clock.instant())) throw BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "Refresh token expired");
        User user = users.findById(userId).filter(User::isEnabled)
                .orElseThrow(() -> BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "Invalid refresh token"));
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE id = ?", id);
        return issueTokens(user, family);
    }

    @Transactional
    public void logout(String rawToken) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE family_id IN "
                + "(SELECT family_id FROM refresh_tokens WHERE token_hash = ?) AND revoked_at IS NULL", sha256(rawToken));
    }

    private TokenResponse issueTokens(User user, UUID family) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = clock.instant().plus(props.jwt().refreshTtl());
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, expires_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), user.getId(), sha256(raw), family, java.sql.Timestamp.from(expires));
        String access = jwt.issueAccessToken(user.getId(), user.getEmail(), user.getRoles());
        return new TokenResponse(access, raw, "Bearer", jwt.accessTtlSeconds());
    }

    private static BusinessException invalidCredentials() {
        return BusinessException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
