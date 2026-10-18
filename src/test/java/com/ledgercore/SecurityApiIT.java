package com.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgercore.config.AppProperties;
import com.ledgercore.security.JwtService;
import com.ledgercore.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Authentication, authorisation (roles and permissions), token handling and data-isolation checks over real HTTP semantics. */
class SecurityApiIT extends AbstractApiIT {
    @Autowired AppProperties props;

    @Test
    void protectedEndpoints_requireAuthentication() throws Exception {
        getJson("/api/v1/accounts", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        getJson("/api/v1/admin/stats", null).andExpect(status().isUnauthorized());
        getJson("/api/v1/users/me", "not-a-jwt").andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedAndExpiredTokens_areRejected() throws Exception {
        String token = registerAndLogin(newEmail());
        getJson("/api/v1/users/me", token).andExpect(status().isOk());

        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");
        getJson("/api/v1/users/me", tampered).andExpect(status().isUnauthorized());

        // a correctly signed token that expired two hours ago
        JwtService past = new JwtService(props, Clock.offset(Clock.systemUTC(), Duration.ofHours(-2)));
        String expired = past.issueAccessToken(UUID.randomUUID(), "x@test.io", EnumSet.of(Role.CUSTOMER));
        getJson("/api/v1/users/me", expired).andExpect(status().isUnauthorized());
    }

    @Test
    void registration_validatesInput_andRejectsDuplicates() throws Exception {
        postJson("/api/v1/auth/register", null, Map.of("email", "bad", "password", "weak", "fullName", "X"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        String email = newEmail();
        register(email);
        postJson("/api/v1/auth/register", null, Map.of("email", email.toUpperCase(), "password", PASSWORD, "fullName", "Dup"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void repeatedBadPasswords_lockTheAccount_evenForTheCorrectPassword() throws Exception {
        String email = newEmail();
        register(email);
        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/auth/login", null, Map.of("email", email, "password", "Wrong-Password-1"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        postJson("/api/v1/auth/login", null, Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        Integer audited = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'LOGIN_FAILED' AND actor_id = "
                + "(SELECT id FROM users WHERE email = ?)", Integer.class, email);
        assertThat(audited).as("failed logins are audited even though the request errored").isEqualTo(5);
    }

    @Test
    void unknownEmailAndWrongPassword_lookIdentical() throws Exception {
        String email = newEmail();
        register(email);
        String a = postJson("/api/v1/auth/login", null, Map.of("email", email, "password", "Wrong-Password-1"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String b = postJson("/api/v1/auth/login", null, Map.of("email", newEmail(), "password", "Wrong-Password-1"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(a).get("code")).isEqualTo(json.readTree(b).get("code"));
        assertThat(json.readTree(a).get("detail")).isEqualTo(json.readTree(b).get("detail"));
    }

    @Test
    void refreshTokens_rotate_andReuseRevokesTheWholeFamily() throws Exception {
        String email = newEmail();
        register(email);
        JsonNode first = login(email, PASSWORD);
        String r1 = first.get("refreshToken").asText();

        JsonNode second = read(postJson("/api/v1/auth/refresh", null, Map.of("refreshToken", r1)).andExpect(status().isOk()));
        String r2 = second.get("refreshToken").asText();
        assertThat(r2).isNotEqualTo(r1);

        // replaying the already-used token is treated as theft: it fails AND kills the new token too
        postJson("/api/v1/auth/refresh", null, Map.of("refreshToken", r1)).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", null, Map.of("refreshToken", r2)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'REFRESH_TOKEN_REUSE_DETECTED'", Integer.class)).isPositive();
    }

    @Test
    void customer_cannotReachBackOfficeEndpoints() throws Exception {
        String token = registerAndLogin(newEmail());
        getJson("/api/v1/admin/users", token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        getJson("/api/v1/admin/audit-logs", token).andExpect(status().isForbidden());
        getJson("/api/v1/admin/stats", token).andExpect(status().isForbidden());
        postJson("/api/v1/admin/reconciliation/run", token, Map.of()).andExpect(status().isForbidden());
    }

    @Test
    void permissions_areEnforcedPerRole() throws Exception {
        String customer = registerAndLogin(newEmail());
        JsonNode acct = openAccount(customer, "NGN");
        String accountId = acct.get("id").asText();

        String auditor = userWithRole("AUDITOR");
        getJson("/api/v1/admin/audit-logs", auditor).andExpect(status().isOk());
        getJson("/api/v1/admin/accounts", auditor).andExpect(status().isOk());
        postJson("/api/v1/admin/accounts/" + accountId + "/freeze", auditor, Map.of()).andExpect(status().isForbidden());
        getJson("/api/v1/admin/users", auditor).andExpect(status().isForbidden());

        String support = userWithRole("SUPPORT");
        getJson("/api/v1/admin/accounts", support).andExpect(status().isOk());
        getJson("/api/v1/admin/audit-logs", support).andExpect(status().isForbidden());
        postWithKey("/api/v1/admin/accounts/" + accountId + "/deposit", support, "dep-" + UUID.randomUUID(),
                Map.of("amountMinor", 1000, "description", "nope")).andExpect(status().isForbidden());

        String admin = adminToken();
        postWithKey("/api/v1/admin/accounts/" + accountId + "/deposit", admin, "dep-" + UUID.randomUUID(),
                Map.of("amountMinor", 1000, "description", "ok")).andExpect(status().isCreated());
        postJson("/api/v1/admin/accounts/" + accountId + "/freeze?reason=test", admin, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void customers_cannotSeeEachOthersAccountsOrTransactions() throws Exception {
        String alice = registerAndLogin(newEmail());
        String bob = registerAndLogin(newEmail());
        String aliceAccount = openAccount(alice, "NGN").get("id").asText();
        openAccount(bob, "NGN");

        getJson("/api/v1/accounts/" + aliceAccount, alice).andExpect(status().isOk());
        getJson("/api/v1/accounts/" + aliceAccount, bob).andExpect(status().isNotFound());            // not 403: no existence leak
        getJson("/api/v1/accounts/" + aliceAccount + "/transactions", bob).andExpect(status().isNotFound());
        getJson("/api/v1/accounts", bob).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aCustomer_cannotSpendFromSomeoneElsesAccount() throws Exception {
        String alice = registerAndLogin(newEmail());
        String mallory = registerAndLogin(newEmail());
        JsonNode aliceAcct = openAccount(alice, "NGN");
        JsonNode malloryAcct = openAccount(mallory, "NGN");
        postWithKey("/api/v1/admin/accounts/" + aliceAcct.get("id").asText() + "/deposit", adminToken(), "dep-" + UUID.randomUUID(),
                Map.of("amountMinor", 100_000, "description", "seed")).andExpect(status().isCreated());

        postWithKey("/api/v1/transfers", mallory, "steal-" + UUID.randomUUID(),
                Map.of("fromAccountId", aliceAcct.get("id").asText(), "toAccountNumber", malloryAcct.get("accountNumber").asText(),
                        "amountMinor", 50_000, "currency", "NGN", "description", "gimme"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        assertThat(balanceOf(aliceAcct.get("id").asText())).isEqualTo(100_000);
    }

    @Test
    void disablingAUser_blocksMoneyMovementImmediately_andRevokesRefreshTokens() throws Exception {
        String email = newEmail();
        register(email);
        JsonNode tokens = login(email, PASSWORD);
        String access = tokens.get("accessToken").asText();
        JsonNode mine = openAccount(access, "NGN");
        JsonNode other = openAccount(registerAndLogin(newEmail()), "NGN");
        String admin = adminToken();
        postWithKey("/api/v1/admin/accounts/" + mine.get("id").asText() + "/deposit", admin, "dep-" + UUID.randomUUID(),
                Map.of("amountMinor", 5_000, "description", "seed")).andExpect(status().isCreated());

        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        patchJson("/api/v1/admin/users/" + userId + "/enabled", admin, Map.of("enabled", false)).andExpect(status().isOk());

        // the access token is still cryptographically valid for minutes, but cannot move money
        postWithKey("/api/v1/transfers", access, "tx-" + UUID.randomUUID(),
                Map.of("fromAccountId", mine.get("id").asText(), "toAccountNumber", other.get("accountNumber").asText(),
                        "amountMinor", 100, "currency", "NGN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_DISABLED"));
        postJson("/api/v1/auth/refresh", null, Map.of("refreshToken", tokens.get("refreshToken").asText())).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", null, Map.of("email", email, "password", PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    void transferRequests_areValidated() throws Exception {
        String token = registerAndLogin(newEmail());
        JsonNode acct = openAccount(token, "NGN");
        Map<String, Object> ok = Map.of("fromAccountId", acct.get("id").asText(), "toAccountNumber", "1234567890", "amountMinor", 100, "currency", "NGN");

        mvcPostNoKey("/api/v1/transfers", token, ok).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        postWithKey("/api/v1/transfers", token, "bad", ok)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        postWithKey("/api/v1/transfers", token, "neg-" + UUID.randomUUID(),
                Map.of("fromAccountId", acct.get("id").asText(), "toAccountNumber", "1234567890", "amountMinor", -5, "currency", "NGN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postWithKey("/api/v1/transfers", token, "cur-" + UUID.randomUUID(),
                Map.of("fromAccountId", acct.get("id").asText(), "toAccountNumber", "12345", "amountMinor", 100, "currency", "ngn"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions mvcPostNoKey(String url, String token, Object payload) throws Exception {
        return postJson(url, token, payload);
    }

    private long balanceOf(String accountId) {
        Long b = jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?::uuid", Long.class, accountId);
        return b == null ? 0 : b;
    }
}
