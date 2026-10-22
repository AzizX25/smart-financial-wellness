package com.azizx25.mockbank.admin;

import java.time.Clock;

import com.azizx25.mockbank.admin.persistence.BankAdministratorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DemoAdminBootstrapActivationTests {
    private ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .withUserConfiguration(DemoAdminBootstrap.class)
                .withBean(BankAdministratorRepository.class, () -> mock(BankAdministratorRepository.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                .withBean(EntityManager.class, () -> mock(EntityManager.class))
                .withBean(Clock.class, Clock::systemUTC);
    }

    @Test
    void defaultProfileCannotBootstrapEvenWithTheFlagEnabled() {
        runner().withPropertyValues("app.demo-admin-bootstrap.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DemoAdminBootstrap.class));
    }

    @Test
    void demoProfileRequiresExplicitOptIn() {
        runner("demo").run(context -> assertThat(context).doesNotHaveBean(DemoAdminBootstrap.class));
    }

    @Test
    void demoProfileWithOptInRegistersTheStartupRunner() {
        runner("demo").withPropertyValues("app.demo-admin-bootstrap.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DemoAdminBootstrap.class));
    }

    @Test
    void productionProfilesBlockBootstrapEvenWhenDemoIsAlsoActive() {
        for (String profile : new String[] {"prod", "production"}) {
            runner("demo", profile).withPropertyValues("app.demo-admin-bootstrap.enabled=true")
                    .run(context -> assertThat(context).doesNotHaveBean(DemoAdminBootstrap.class));
        }
    }
}
