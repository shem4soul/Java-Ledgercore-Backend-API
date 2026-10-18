package com.ledgercore.admin;

import com.ledgercore.audit.AuditService;
import com.ledgercore.config.AppProperties;
import com.ledgercore.security.Role;
import com.ledgercore.user.User;
import com.ledgercore.user.UserRepository;
import java.util.EnumSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the first ADMIN from environment variables if (and only if) that email does not exist yet. */
@Component
public class BootstrapAdmin implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AppProperties props;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public BootstrapAdmin(AppProperties props, UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Bootstrap b = props.bootstrap();
        if (b == null || b.adminEmail() == null || b.adminEmail().isBlank() || b.adminPassword() == null || b.adminPassword().isBlank()) return;
        if (users.existsByEmailIgnoreCase(b.adminEmail())) return;
        User admin = users.save(new User(b.adminEmail(), encoder.encode(b.adminPassword()), "System Administrator", EnumSet.of(Role.ADMIN, Role.CUSTOMER)));
        audit.record(admin.getId(), "ADMIN_BOOTSTRAPPED", "USER", admin.getId().toString(), Map.of());
        log.warn("Bootstrap admin {} created. Change this password immediately.", b.adminEmail());
    }
}
