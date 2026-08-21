package com.azizx25.mockbank;

import com.azizx25.mockbank.demo.SupervisorDemoFixtureService;
import com.azizx25.mockbank.demo.api.SupervisorDemoFixtureController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.demo-fixtures.enabled=false")
@AutoConfigureMockMvc
@Import(MockBankPostgresTestConfiguration.class)
class SupervisorDemoDisabledIntegrationTests {

    @Autowired ApplicationContext context;
    @Autowired MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    void defaultConfigurationDoesNotExposeDemoMutationEndpoint() throws Exception {
        assertThat(context.getBeansOfType(SupervisorDemoFixtureController.class)).isEmpty();
        assertThat(context.getBeansOfType(SupervisorDemoFixtureService.class)).isEmpty();

        mockMvc.perform(post("/api/admin/demo-fixtures/supervisor")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
