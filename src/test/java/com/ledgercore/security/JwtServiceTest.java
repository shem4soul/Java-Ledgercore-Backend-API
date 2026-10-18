package com.ledgercore.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgercore.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
    private static final String SECRET = "unit-test-secret-unit-test-secret-1234";

    private AppProperties props(String secret) {
        return new AppProperties(new AppProperties.Jwt(secret, "ledgercore", Duration.ofMinutes(15), Duration.ofDays(7)),
                null, null, null, null);
    }

    @Test
    void roundTrips_identityAndRoles() {
        JwtService jwt = new JwtService(props(SECRET), Clock.systemUTC());
        UUID id = UUID.randomUUID();
        String token = jwt.issueAccessToken(id, "a@b.io", EnumSet.of(Role.CUSTOMER, Role.AUDITOR));
        AuthenticatedUser u = jwt.parse(token).orElseThrow();
        assertThat(u.id()).isEqualTo(id);
        assertThat(u.email()).isEqualTo("a@b.io");
        assertThat(u.roles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.AUDITOR);
        assertThat(u.can(Permission.AUDIT_READ)).isTrue();
        assertThat(u.can(Permission.LEDGER_ADJUST)).isFalse();
    }

    @Test
    void rejectsTokensSignedWithAnotherKey() {
        JwtService mine = new JwtService(props(SECRET), Clock.systemUTC());
        JwtService attacker = new JwtService(props("attacker-secret-attacker-secret-1234567"), Clock.systemUTC());
        String forged = attacker.issueAccessToken(UUID.randomUUID(), "evil@x.io", EnumSet.of(Role.ADMIN));
        assertThat(mine.parse(forged)).isEmpty();
    }

    @Test
    void rejectsExpiredTokens() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String old = new JwtService(props(SECRET), past).issueAccessToken(UUID.randomUUID(), "a@b.io", EnumSet.of(Role.CUSTOMER));
        assertThat(new JwtService(props(SECRET), Clock.systemUTC()).parse(old)).isEmpty();
    }

    @Test
    void rejectsGarbageAndEmpty() {
        JwtService jwt = new JwtService(props(SECRET), Clock.systemUTC());
        assertThat(jwt.parse("garbage")).isEmpty();
        assertThat(jwt.parse("")).isEmpty();
        assertThat(jwt.parse("a.b.c")).isEmpty();
    }

    @Test
    void adminHoldsEveryPermission_customerOnlyItsOwn() {
        assertThat(Role.ADMIN.permissions()).containsAll(EnumSet.allOf(Permission.class));
        assertThat(Role.CUSTOMER.permissions()).containsExactlyInAnyOrder(Permission.TRANSFER_CREATE, Permission.ACCOUNT_READ_OWN);
        assertThat(Role.SUPPORT.permissions()).doesNotContain(Permission.LEDGER_ADJUST, Permission.ACCOUNT_FREEZE);
    }
}
