package com.azizx25.mockbank;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.azizx25.mockbank.admin.DemoAdminBootstrap;
import com.azizx25.mockbank.admin.persistence.BankAdministratorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.demo-admin-bootstrap.enabled=true",
        "app.demo-admin-bootstrap.username=local.setup.test"
})
@AutoConfigureMockMvc
@Import(MockBankPostgresTestConfiguration.class)
@ActiveProfiles("demo")
@ExtendWith(OutputCaptureExtension.class)
class DemoAdminBootstrapIntegrationTests {
    private static final String PASSWORD = randomPassword();

    @DynamicPropertySource
    static void bootstrapPassword(DynamicPropertyRegistry properties) {
        properties.add("app.demo-admin-bootstrap.password", () -> PASSWORD);
    }

    @Autowired DemoAdminBootstrap startup;
    @Autowired BankAdministratorRepository administrators;
    @Autowired PasswordEncoder encoder;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String username() {
        return "bootstrap.test." + UUID.randomUUID();
    }

    private void provision(String username, String password) {
        MockEnvironment configuration = new MockEnvironment()
                .withProperty("app.demo-admin-bootstrap.username", username)
                .withProperty("app.demo-admin-bootstrap.password", password);
        DemoAdminBootstrap runner = new DemoAdminBootstrap(administrators, encoder, entityManager, configuration, clock);
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> runner.run(null));
    }

    private String hash(String username) {
        return jdbc.queryForObject("SELECT password_hash FROM mock_bank.bank_administrator WHERE username=?", String.class, username);
    }

    @AfterEach
    void removeOnlyTestAdministrators() {
        jdbc.update("DELETE FROM mock_bank.bank_administrator WHERE username LIKE 'bootstrap.test.%'");
    }

    @Test
    void firstStartStoresOnlyHashAndUsesNormalAdminLogin(CapturedOutput output) throws Exception {
        String stored = hash("local.setup.test");
        assertThat(stored.startsWith("$2")).isTrue();
        assertThat(PASSWORD.equals(stored)).isFalse();
        assertThat(encoder.matches(PASSWORD, stored)).isTrue();
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "local.setup.test", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("username").value("local.setup.test"))
                .andExpect(jsonPath("accessToken").isNotEmpty());
        assertThat(output.getAll().contains(PASSWORD)).isFalse();
        assertThat(output.getAll().contains(stored)).isFalse();
    }

    @Test
    void repeatStartKeepsHashIdentityAndOtherAdministratorsUnchanged() {
        String before = hash("local.setup.test");
        String legacy = hash("admin.demo");
        long count = administrators.count();
        UUID id = administrators.findByUsername("local.setup.test").orElseThrow().getId();
        startup.run(null);
        assertThat(hash("local.setup.test").equals(before)).isTrue();
        assertThat(hash("admin.demo").equals(legacy)).isTrue();
        assertThat(administrators.count()).isEqualTo(count);
        assertThat(administrators.findByUsername("local.setup.test").orElseThrow().getId()).isEqualTo(id);
    }

    @Test
    void differentPasswordCannotOverwriteAnExistingAdministrator(CapturedOutput output) {
        String before = hash("local.setup.test");
        String other = randomPassword();
        IllegalStateException failure = catchThrowableOfType(IllegalStateException.class,
                () -> provision("local.setup.test", other));
        assertThat(failure.getMessage()).contains("no account was changed");
        assertThat(hash("local.setup.test").equals(before)).isTrue();
        assertThat(failure.getMessage().contains(other)).isFalse();
        assertThat(output.getAll().contains(other)).isFalse();
    }

    @Test
    void missingAndInvalidConfigurationCannotCreateAnyAdministrator() {
        long count = administrators.count();
        for (String invalid : new String[] {"", " ", "too-short", "é".repeat(37)}) {
            IllegalStateException failure = catchThrowableOfType(IllegalStateException.class,
                    () -> provision(username(), invalid));
            assertThat(failure.getMessage()).contains("12-72 UTF-8 bytes");
        }
        assertThat(catchThrowableOfType(IllegalStateException.class,
                () -> provision("not a valid username", PASSWORD)).getMessage()).contains("username");
        assertThat(administrators.count()).isEqualTo(count);
    }

    @Test
    void exactUtf8ByteLimitAndUsernameNormalizationAreSupported() {
        String username = username();
        String password = "é".repeat(36);
        assertThat(password.getBytes(StandardCharsets.UTF_8).length).isEqualTo(72);
        provision(" " + username.toUpperCase(java.util.Locale.ROOT) + " ", password);
        assertThat(encoder.matches(password, hash(username))).isTrue();
    }

    @Test
    void inactiveAdministratorCannotBeReactivatedByBootstrap() {
        String username = username();
        provision(username, PASSWORD);
        String before = hash(username);
        jdbc.update("UPDATE mock_bank.bank_administrator SET status='INACTIVE' WHERE username=?", username);
        assertThat(catchThrowableOfType(IllegalStateException.class,
                () -> provision(username, PASSWORD)).getMessage()).contains("inactive");
        assertThat(hash(username).equals(before)).isTrue();
        assertThat(administrators.findByUsername(username).orElseThrow().getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void legacyMigrationAccountCannotBeReplacedByNewLocalCredentials() {
        String before = hash("admin.demo");
        assertThat(catchThrowableOfType(IllegalStateException.class,
                () -> provision("admin.demo", PASSWORD)).getMessage()).contains("no account was changed");
        assertThat(hash("admin.demo").equals(before)).isTrue();
    }

    @Test
    void concurrentFirstStartsCreateOnlyOneAdministrator() throws Exception {
        String username = username();
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> { start.await(); provision(username, PASSWORD); return true; });
            var second = workers.submit(() -> { start.await(); provision(username, PASSWORD); return true; });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            workers.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_bank.bank_administrator WHERE username=?", Long.class, username)).isEqualTo(1L);
        assertThat(encoder.matches(PASSWORD, hash(username))).isTrue();
    }
}
