package com.azizx25.mockbank.admin;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import com.azizx25.mockbank.admin.persistence.BankAdministrator;
import com.azizx25.mockbank.admin.persistence.BankAdministratorRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Local startup provisioning only; there is no HTTP bootstrap endpoint. */
@Component
@Profile("demo & !prod & !production")
@ConditionalOnProperty(name = "app.demo-admin-bootstrap.enabled", havingValue = "true")
public class DemoAdminBootstrap implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoAdminBootstrap.class);

    private final BankAdministratorRepository administrators;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final Environment environment;
    private final Clock clock;

    public DemoAdminBootstrap(BankAdministratorRepository administrators, PasswordEncoder passwordEncoder,
            EntityManager entityManager, Environment environment, Clock clock) {
        this.administrators = administrators;
        this.passwordEncoder = passwordEncoder;
        this.entityManager = entityManager;
        this.environment = environment;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        String suppliedUsername = environment.getProperty("app.demo-admin-bootstrap.username", "local.demo.admin");
        String username = suppliedUsername.strip().toLowerCase(Locale.ROOT);
        String password = environment.getProperty("app.demo-admin-bootstrap.password", "");
        if (!username.matches("[a-z0-9][a-z0-9._-]{0,79}")) {
            throw new IllegalStateException("Local administrator username must use 1-80 letters, digits, dots, underscores or hyphens.");
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.isBlank() || passwordBytes < 12 || passwordBytes > 72) {
            throw new IllegalStateException("Local administrator password must contain 12-72 UTF-8 bytes; no account was changed.");
        }

        // Serialize concurrent first starts. Only an integer result is mapped;
        // the PostgreSQL transaction-level lock is released at commit/rollback.
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(1937200411)").getSingleResult();
        BankAdministrator existing = administrators.findByUsername(username).orElse(null);
        if (existing != null) {
            if (!"ACTIVE".equals(existing.getStatus()) || !passwordEncoder.matches(password, existing.getPasswordHash())) {
                throw new IllegalStateException("Local administrator already exists with different credentials or is inactive; no account was changed.");
            }
            return;
        }
        administrators.saveAndFlush(BankAdministrator.createLocal(
                username, passwordEncoder.encode(password), OffsetDateTime.now(clock)));
        // Deliberately omit usernames, passwords, hashes and configuration values.
        LOGGER.info("Local demo administrator initialized");
    }
}
