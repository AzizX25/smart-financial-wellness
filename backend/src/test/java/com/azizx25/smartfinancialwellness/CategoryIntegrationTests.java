package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, BankConnectionIntegrationTests.BankProviderTestConfiguration.class})
class CategoryIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final String BANK_PASSWORD = "DemoBank2026!";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired BankConnectionIntegrationTests.FakeMockBankProvider provider;

    @BeforeEach
    void resetProvider() { provider.reset(); }

    @Test
    void systemSeedAndPersonalCrudRespectValidationReadOnlyAndOwnership() throws Exception {
        AppRegistration owner = registerAndActivate();
        AppRegistration other = registerAndActivate();

        mockMvc.perform(get("/api/categories").header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.system == true)]", hasSize(17)))
                .andExpect(jsonPath("$[?(@.code == 'FOOD')].name").value("Alimentation et courses"));

        UUID personalId = createCategory(owner, "  Animaux   domestiques  ", "EXPENSE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT label FROM " + SCHEMA + "category WHERE id = ?", String.class, personalId))
                .isEqualTo("Animaux domestiques");

        mockMvc.perform(post("/api/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ANIMAUX DOMESTIQUES\",\"nature\":\"EXPENSE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_ALREADY_EXISTS"));

        mockMvc.perform(patch("/api/categories/{id}", personalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Intrusion\",\"nature\":\"EXPENSE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        mockMvc.perform(patch("/api/categories/{id}", "00000000-0000-0000-0000-000000000005")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Modification interdite\",\"nature\":\"EXPENSE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CATEGORY_READ_ONLY"));

        mockMvc.perform(patch("/api/categories/{id}", personalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Animaux\",\"nature\":\"EXPENSE\",\"color\":\"#123ABC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Animaux"));
        mockMvc.perform(delete("/api/categories/{id}", personalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isNoContent());
    }

    @Test
    void automaticExactRuleManualCorrectionHistoryAndSynchronizationAreStable() throws Exception {
        AppRegistration user = registerAndActivate();
        connectAndSynchronize(user);
        UUID transactionId = firstTransactionId(user.userId());

        assertThat(jdbcTemplate.queryForMap(
                "SELECT category.code, transaction.category_source, rule.code AS rule_code "
                        + "FROM " + SCHEMA + "transaction_record transaction "
                        + "JOIN " + SCHEMA + "category category ON category.id = transaction.category_id "
                        + "JOIN " + SCHEMA + "categorization_rule rule ON rule.id = transaction.matched_categorization_rule_id "
                        + "WHERE transaction.id = ?", transactionId))
                .containsEntry("code", "FOOD")
                .containsEntry("category_source", "RULES")
                .containsEntry("rule_code", "MONOPRIX_COUNTERPARTY");

        UUID personalId = createCategory(user, "Dépenses familiales", "EXPENSE");
        mockMvc.perform(patch("/api/transactions/{id}/category", transactionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"%s\"}".formatted(personalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentSource").value("MANUAL"));

        mockMvc.perform(post("/api/categorization-runs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categorizedCount").value(0));

        mockMvc.perform(patch("/api/categories/{id}", personalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dépenses familiales\",\"nature\":\"INCOME\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
        synchronize(user);

        mockMvc.perform(get("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].categoryId").value(personalId.toString()))
                .andExpect(jsonPath("$.items[0].categoryAssignmentSource").value("MANUAL"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "transaction_category_history WHERE transaction_id = ?",
                Integer.class, transactionId)).isEqualTo(2);

        mockMvc.perform(delete("/api/categories/{id}", personalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
    }

    @Test
    void unmatchedTransactionsStayUncategorizedAndCrossUserAssignmentsAreHidden() throws Exception {
        provider.replacePrimaryTransaction(new ExternalBankTransaction(
                "UNKNOWN-TX", LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 4),
                new BigDecimal("12.0000"), "CREDIT", "TND", "Prime exceptionnelle", null, "POSTED"));
        AppRegistration first = registerAndActivate();
        AppRegistration second = registerAndActivate();
        connectAndSynchronize(first);
        UUID transactionId = firstTransactionId(first.userId());
        UUID otherCategory = createCategory(second, "Revenu privé", "INCOME");

        mockMvc.perform(post("/api/categorization-runs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(first.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categorizedCount").value(0))
                .andExpect(jsonPath("$.uncategorizedCount").value(1));
        mockMvc.perform(post("/api/categorization-runs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(first.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categorizedCount").value(0));

        mockMvc.perform(patch("/api/transactions/{id}/category", transactionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(first.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"%s\"}".formatted(otherCategory)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        mockMvc.perform(patch("/api/transactions/{id}/category", transactionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(first.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"00000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentSource").value("UNCATEGORIZED"));
        mockMvc.perform(patch("/api/transactions/{id}/category", transactionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(second.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void summariesSeparateCurrenciesAndKeepTransfersDistinctFromExpenses() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID tnd = createManualAccount(user.userId(), "TND");
        UUID eur = createManualAccount(user.userId(), "EUR");
        insertManualTransaction(user.userId(), tnd, category("FOOD"), "DEBIT", "10.0000", "Food");
        insertManualTransaction(user.userId(), tnd, category("TRANSFER_OUT"), "DEBIT", "20.0000", "Transfer");
        insertManualTransaction(user.userId(), tnd, category("UNCATEGORIZED"), "DEBIT", "5.0000", "Unknown");
        insertManualTransaction(user.userId(), eur, category("INCOME_OTHER"), "CREDIT", "100.0000", "Income");

        mockMvc.perform(get("/api/categories/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())).param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencies.length()").value(2))
                .andExpect(jsonPath("$.currencies[?(@.currency == 'TND')].uncategorizedCount").value(1))
                .andExpect(jsonPath("$.currencies[?(@.currency == 'TND')].uncategorizedAmount").value(5.0))
                .andExpect(jsonPath("$.currencies[?(@.currency == 'TND')].categories[?(@.nature == 'TRANSFER')].totalAmount").value(20.0))
                .andExpect(jsonPath("$.currencies[?(@.currency == 'EUR')].categories[?(@.nature == 'INCOME')].totalAmount").value(100.0));
    }

    private AppRegistration registerAndActivate() throws Exception {
        String email = "category-" + UUID.randomUUID() + "@example.test";
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"StrongPass123!\",\"firstName\":\"Category\",\"lastName\":\"Tester\",\"displayName\":\"Category Tester\"}".formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(body.get("user").get("id").asText());
        jdbcTemplate.update("UPDATE " + SCHEMA + "app_user SET status = 'ACTIVE' WHERE id = ?", userId);
        jdbcTemplate.update("UPDATE " + SCHEMA + "authentication_identity SET status = 'ACTIVE', verified_at = CURRENT_TIMESTAMP WHERE user_id = ?", userId);
        return new AppRegistration(userId, body.get("accessToken").asText());
    }

    private UUID createCategory(AppRegistration user, String name, String nature) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"nature\":\"%s\"}".formatted(name, nature)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void connectAndSynchronize(AppRegistration user) throws Exception {
        mockMvc.perform(post("/api/bank-connections/mock-bank")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"category-%s\",\"password\":\"%s\"}".formatted(UUID.randomUUID(), BANK_PASSWORD)))
                .andExpect(status().isOk());
        synchronize(user);
    }

    private void synchronize(AppRegistration user) throws Exception {
        mockMvc.perform(post("/api/bank/synchronizations").header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk());
    }

    private UUID firstTransactionId(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT id FROM " + SCHEMA + "transaction_record WHERE user_id = ? ORDER BY id LIMIT 1", UUID.class, userId);
    }

    private UUID createManualAccount(UUID userId, String currency) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "financial_account (id,user_id,account_origin,name,masked_identifier,account_type,currency,status) VALUES (?,?,'MANUAL','Test account','****9999','OTHER',?,'ACTIVE')", id, userId, currency);
        return id;
    }

    private UUID category(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM " + SCHEMA + "category WHERE code = ? AND user_id IS NULL", UUID.class, code);
    }

    private void insertManualTransaction(UUID userId, UUID accountId, UUID categoryId, String direction, String amount, String description) {
        boolean uncategorized = categoryId.equals(category("UNCATEGORIZED"));
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "transaction_record (id,user_id,financial_account_id,category_id,source_type,client_idempotency_key,booking_date,amount,direction,currency,raw_description,normalized_description,category_source,categorized_at) VALUES (?,?,?,?,'MANUAL',?,DATE '2026-08-04',?,?,(SELECT currency FROM " + SCHEMA + "financial_account WHERE id = ?),?,?,?,?)",
                UUID.randomUUID(), userId, accountId, categoryId, UUID.randomUUID().toString(), new BigDecimal(amount), direction, accountId, description, description.toLowerCase(), uncategorized ? "UNCATEGORIZED" : "USER", uncategorized ? null : java.sql.Timestamp.from(java.time.Instant.now()));
    }

    private String bearer(String token) { return "Bearer " + token; }
    private record AppRegistration(UUID userId, String token) { }
}
