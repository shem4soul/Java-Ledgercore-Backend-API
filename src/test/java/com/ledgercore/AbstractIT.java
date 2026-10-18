package com.ledgercore;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountService;
import com.ledgercore.idempotency.IdempotencyService;
import com.ledgercore.idempotency.IdempotencyService.Outcome;
import com.ledgercore.ledger.AdminLedgerService;
import com.ledgercore.ledger.LedgerHooks;
import com.ledgercore.ledger.ReconciliationService;
import com.ledgercore.ledger.TransferDtos.TransferRequest;
import com.ledgercore.ledger.TransferDtos.TransferResponse;
import com.ledgercore.ledger.TransferService;
import com.ledgercore.security.AuthenticatedUser;
import com.ledgercore.security.Role;
import com.ledgercore.user.AuthService;
import com.ledgercore.user.User;
import com.ledgercore.user.UserDtos.RegisterRequest;
import com.ledgercore.user.UserRepository;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests. One PostgreSQL 16 container is shared by every test class (singleton pattern) and the real
 * Flyway migrations, triggers and constraints run against it, exactly as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class AbstractIT {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected AuthService authService;
    @Autowired protected AccountService accountService;
    @Autowired protected AdminLedgerService adminLedger;
    @Autowired protected TransferService transferService;
    @Autowired protected IdempotencyService idempotency;
    @Autowired protected ReconciliationService reconciliation;
    @Autowired protected UserRepository userRepository;
    @Autowired protected LedgerHooks hooks;

    protected record Customer(AuthenticatedUser user, Account account) {}

    /** Outbox events from earlier tests must not interfere with the one under test. */
    @BeforeEach
    void drainOutbox() {
        jdbc.update("UPDATE outbox_events SET status = 'SENT' WHERE status <> 'SENT'");
    }

    @AfterEach
    void clearHooks() {
        hooks.clear();
    }

    protected UUID adminId() {
        return userRepository.findByEmailIgnoreCase("admin@ledgercore.test").orElseThrow().getId();
    }

    /** A new customer with one NGN account holding {@code balanceMinor}. */
    protected Customer customer(long balanceMinor) {
        String email = "u" + UUID.randomUUID() + "@test.io";
        User u = authService.register(new RegisterRequest(email, "Passw0rd-Test1", "Test Customer"));
        AuthenticatedUser au = new AuthenticatedUser(u.getId(), u.getEmail(), EnumSet.of(Role.CUSTOMER));
        Account a = accountService.open(u.getId(), "NGN");
        if (balanceMinor > 0) adminLedger.deposit(adminId(), a.getId(), balanceMinor, "seed");
        return new Customer(au, a);
    }

    protected Outcome<TransferResponse> sendOutcome(Customer from, Account to, long amountMinor, String key) {
        TransferRequest req = new TransferRequest(from.account().getId(), to.getAccountNumber(), amountMinor, "NGN", "test");
        String hash = IdempotencyService.hash(req.fromAccountId().toString(), req.toAccountNumber(),
                String.valueOf(req.amountMinor()), req.currency(), req.description());
        return idempotency.execute(from.user().id(), key, "TRANSFER", hash, TransferResponse.class,
                () -> transferService.transfer(from.user(), req));
    }

    protected TransferResponse send(Customer from, Account to, long amountMinor, String key) {
        return sendOutcome(from, to, amountMinor, key).body();
    }

    protected long balance(Account a) {
        Long b = jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, a.getId());
        return b == null ? 0 : b;
    }
}
