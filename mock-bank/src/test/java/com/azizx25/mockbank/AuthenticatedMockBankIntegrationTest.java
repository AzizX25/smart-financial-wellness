package com.azizx25.mockbank;

import com.azizx25.mockbank.security.MockBankJwtService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

abstract class AuthenticatedMockBankIntegrationTest {

    protected MockMvc mockMvc;

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private MockBankJwtService jwtService;

    @BeforeEach
    void configureAuthenticatedMockMvc() {
        String token = jwtService.issue("MOCK-CUST-001").value();
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .defaultRequest(get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .apply(springSecurity())
                .build();
    }
}
