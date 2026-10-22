package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.azizx25.smartfinancialwellness.recommendation.RecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class RecommendationApiIntegrationTests {
    private static final String S = "smart_financial_wellness.";
    private static final UUID FOOD = UUID.fromString("00000000-0000-0000-0000-000000000005");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecommendationService service;
    @Autowired com.azizx25.smartfinancialwellness.score.FinancialScoreService scores;
    @MockitoBean com.azizx25.smartfinancialwellness.assistance.AssistanceClient assistance;

    @Test
    void futureReceiptDateProducesAnEditablePartialPreviewWithoutPersistingAnything() throws Exception {
        var owner = user(true);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(20, 20,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
        org.mockito.Mockito.when(assistance.receipt(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new com.azizx25.smartfinancialwellness.assistance.AssistanceClient.ReceiptResult(
                    "local-financial-assistance-v1", "EXTRACTED", "Lefties",
                    java.time.LocalDate.now(ZoneId.of("Africa/Tunis")).plusMonths(1).toString(), "309.700", "TND");
        });
        mvc.perform(multipart("/api/receipts/extract")
                .file(new MockMultipartFile("file", "synthetic.png", "image/png", output.toByteArray()))
                .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.date").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.merchant").value("Lefties"))
                .andExpect(jsonPath("$.amount").value("309.700"))
                .andExpect(jsonPath("$.currency").value("TND"))
                .andExpect(jsonPath("$.asOfDate").value(java.time.LocalDate.now(ZoneId.of("Africa/Tunis")).toString()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "transaction_record WHERE user_id=?", Integer.class, owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "source_file WHERE user_id=?", Integer.class, owner.id())).isZero();
    }

    @Test
    void explanationUsesOwnedSavedFactsOutsideDatabaseTransactionsAndHasDeterministicFallback() throws Exception {
        var owner = user(true);
        history(owner.id(), "TND", "950.0000");
        var item = generate(owner, "TND").get("items").get(0);
        String id = item.get("id").asText();
        org.mockito.Mockito.when(assistance.explain(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new com.azizx25.smartfinancialwellness.assistance.AssistanceClient.ExplanationResult(
                    "local-financial-assistance-v1", "EXPLAINED", "Comparez les dépenses et votre budget.",
                    java.util.List.of(invocation.getArgument(1, String.class)));
        });
        mvc.perform(post("/api/recommendations/{id}/explanation", id).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("LOCAL_LLM"))
                .andExpect(jsonPath("$.factCode").value(item.get("fact").get("code").asText()));
        var other = user(true);
        mvc.perform(post("/api/recommendations/{id}/explanation", id).header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verify(assistance, org.mockito.Mockito.times(1)).explain(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.when(assistance.explain(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new com.azizx25.smartfinancialwellness.assistance.AssistanceClient.ExplanationResult(
                        "local-financial-assistance-v1", "UNAVAILABLE", null, java.util.List.of()));
        mvc.perform(post("/api/recommendations/{id}/explanation", id).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("RULES"))
                .andExpect(jsonPath("$.text").value(item.get("message").asText()));
    }

    @Test
    void receiptRequiresActiveAuthenticationAndPersistsNothingWhileValidatingImages() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(20, 20,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
        var image = new MockMultipartFile("file", "synthetic.png", "image/png", output.toByteArray());
        mvc.perform(multipart("/api/receipts/extract").file(image)).andExpect(status().isUnauthorized());
        var pending = user(false);
        mvc.perform(multipart("/api/receipts/extract").file(image).header(HttpHeaders.AUTHORIZATION, bearer(pending)))
                .andExpect(status().isForbidden());
        var owner = user(true);
        org.mockito.Mockito.when(assistance.receipt(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new com.azizx25.smartfinancialwellness.assistance.AssistanceClient.ReceiptResult(
                    "local-financial-assistance-v1", "EXTRACTED", "Synthetic café",
                    YearMonth.now(ZoneId.of("Africa/Tunis")).atDay(1).toString(), "12.3456", "TND");
        });
        mvc.perform(multipart("/api/receipts/extract").file(image).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXTRACTED"))
                .andExpect(jsonPath("$.amount").value("12.3456"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "transaction_record WHERE user_id=?",
                Integer.class, owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "source_file WHERE user_id=?",
                Integer.class, owner.id())).isZero();
        mvc.perform(multipart("/api/receipts/extract")
                .file(new MockMultipartFile("file", "bad.png", "image/png", new byte[]{1, 2, 3}))
                .header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/receipts/extract").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generatesJustifiedIdempotentRecommendationsAndPersistsFeedbackWithoutFinancialMutations() throws Exception {
        var user = user(true);
        history(user.id(), "TND", "950.0000");
        var first = generate(user, "TND");
        assertThat(first.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(first.get("items").size()).isEqualTo(2);
        var item = first.get("items").get(0);
        assertThat(item.get("rulesVersion").asText()).isEqualTo("financial-recommendations-v1");
        assertThat(item.get("fact").get("value").isNumber()).isTrue();
        assertThat(item.has("userId")).isFalse();
        assertThat(generate(user, "TND").get("items")).isEqualTo(first.get("items"));
        assertThat(count(user.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S
                + "audit_log_entry WHERE actor_id=? AND action='RECOMMENDATIONS_GENERATED'", Integer.class, user.id())).isOne();
        String id = item.get("id").asText();
        feedback(user, id, "ACCEPTED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        feedback(user, id, "ACCEPTED").andExpect(status().isOk());
        var repeated = generate(user, "TND");
        assertThat(repeated.get("items").get(0).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "goal_contribution WHERE financial_goal_id IN "
                + "(SELECT id FROM " + S + "financial_goal WHERE user_id=?)", Integer.class, user.id())).isZero();
    }

    @Test
    void enforcesAuthenticationStatusOwnershipCurrencyAndStrictFeedback() throws Exception {
        mvc.perform(post("/api/recommendations/current").param("currency", "TND")).andExpect(status().isUnauthorized());
        var pending = user(false);
        mvc.perform(get("/api/recommendations").header(HttpHeaders.AUTHORIZATION, bearer(pending))
                .param("currency", "TND")).andExpect(status().isForbidden());
        var owner = user(true);
        history(owner.id(), "TND", "950.0000");
        String id = generate(owner, "TND").get("items").get(0).get("id").asText();
        var other = user(true);
        feedback(other, id, "HIDDEN").andExpect(status().isNotFound());
        mvc.perform(get("/api/recommendations").header(HttpHeaders.AUTHORIZATION, bearer(other))
                .param("currency", "TND")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/recommendations").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .param("currency", "EUR")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/api/recommendations/current").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .param("currency", "tnd")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/recommendations/{id}/status", id).header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\",\"userId\":\"other\"}"))
                .andExpect(status().isBadRequest());
        feedback(owner, id, "ACTIVE").andExpect(status().isBadRequest());
        feedback(owner, id, "REJECTED").andExpect(status().isOk());
    }

    @Test
    void distinguishesInsufficientHistoryAndNoActionNeededWithoutFabrication() throws Exception {
        var empty = user(true);
        assertThat(generate(empty, "TND").get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(count(empty.id())).isZero();
        var healthy = user(true);
        history(healthy.id(), "TND", "700.0000");
        assertThat(generate(healthy, "TND").get("status").asText()).isEqualTo("NO_ACTION_NEEDED");
        assertThat(count(healthy.id())).isZero();
    }

    @Test
    void preservesImmutableFactsOnReadsAndSupersedesOnlyActiveOldRecommendations() throws Exception {
        var user = user(true);
        history(user.id(), "TND", "950.0000");
        var first = generate(user, "TND");
        String id = first.get("items").get(0).get("id").asText();
        feedback(user, id, "HIDDEN").andExpect(status().isOk());
        jdbc.update("UPDATE " + S + "transaction_record SET amount=970 WHERE user_id=? AND direction='DEBIT'", user.id());
        var saved = body(mvc.perform(get("/api/recommendations").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .param("currency", "TND")).andExpect(status().isOk()).andReturn());
        assertThat(saved.get("items").get(0).get("fact")).isEqualTo(first.get("items").get(0).get("fact"));
        var refreshed = generate(user, "TND");
        assertThat(refreshed.get("evaluationId")).isNotEqualTo(first.get("evaluationId"));
        assertThat(jdbc.queryForObject("SELECT status FROM " + S + "personalized_recommendation WHERE id=?",
                String.class, UUID.fromString(id))).isEqualTo("HIDDEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S
                + "personalized_recommendation WHERE user_id=? AND status='OBSOLETE'", Integer.class, user.id())).isOne();
    }

    @Test
    void concurrentGenerationCreatesOneLogicalSet() throws Exception {
        var user = user(true);
        history(user.id(), "TND", "950.0000");
        try (var executor = Executors.newFixedThreadPool(3)) {
            var one = executor.submit(() -> service.generate(user.id(), "TND"));
            var two = executor.submit(() -> service.generate(user.id(), "TND"));
            var score = executor.submit(() -> scores.calculateCurrent(user.id(), "TND"));
            assertThat(one.get().evaluationId()).isEqualTo(two.get().evaluationId());
            assertThat(one.get().evaluationId()).isEqualTo(score.get().id());
        }
        assertThat(count(user.id())).isEqualTo(2);
    }

    @Test
    void migrationRejectsForeignUnavailableAndUnjustifiedSourcesWithoutEditingSnapshots() throws Exception {
        var owner = user(true);
        var foreign = user(true);
        history(owner.id(), "TND", "950.0000");
        history(foreign.id(), "TND", "950.0000");
        var owned = generate(owner, "TND");
        var other = generate(foreign, "TND");
        UUID recommendationId = UUID.fromString(owned.get("items").get(0).get("id").asText());
        UUID foreignFactor = jdbc.queryForObject("SELECT id FROM " + S
                + "evaluation_dimension WHERE wellness_evaluation_id=? AND dimension_type='SAVINGS_RATE'", UUID.class,
                UUID.fromString(other.get("evaluationId").asText()));
        assertThatThrownBy(() -> jdbc.update("UPDATE " + S
                + "recommendation_justification SET evaluation_dimension_id=? WHERE recommendation_id=?",
                foreignFactor, recommendationId)).isInstanceOf(DataAccessException.class);
        UUID unavailable = jdbc.queryForObject("SELECT id FROM " + S
                + "evaluation_dimension WHERE wellness_evaluation_id=? AND dimension_type='BALANCE_RESILIENCE'", UUID.class,
                UUID.fromString(owned.get("evaluationId").asText()));
        assertThatThrownBy(() -> jdbc.update("UPDATE " + S
                + "recommendation_justification SET evaluation_dimension_id=? WHERE recommendation_id=?",
                unavailable, recommendationId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + S
                + "recommendation_justification WHERE recommendation_id=?", recommendationId)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S
                + "flyway_schema_history WHERE version='22' AND success", Integer.class)).isOne();
    }

    private User user(boolean active) throws Exception {
        var response = body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"recommendation-%s@example.test","password":"StrongPass123!",
                         "firstName":"Demo","lastName":"Test","displayName":"Demo Test"}
                        """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn());
        UUID id = UUID.fromString(response.get("user").get("id").asText());
        if (active) {
            jdbc.update("UPDATE " + S + "app_user SET status='ACTIVE' WHERE id=?", id);
            jdbc.update("UPDATE " + S + "authentication_identity SET status='ACTIVE',verified_at=CURRENT_TIMESTAMP WHERE user_id=?", id);
        }
        return new User(id, response.get("accessToken").asText());
    }

    private void history(UUID user, String currency, String expense) {
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO " + S + "financial_account (id,user_id,account_origin,name,account_type,currency,status) "
                + "VALUES (?,?,'MANUAL','Fixture','CHECKING',?,'ACTIVE')", account, user, currency);
        YearMonth month = YearMonth.now(ZoneId.of("Africa/Tunis"));
        for (int previous = 1; previous <= 3; previous++) {
            for (String direction : new String[]{"CREDIT", "DEBIT"}) {
                jdbc.update("INSERT INTO " + S + "transaction_record "
                        + "(user_id,financial_account_id,category_id,source_type,booking_date,amount,direction,currency,raw_description,normalized_description,category_source,categorized_at) "
                        + "VALUES (?,?,?,'MANUAL',?,?,?,?,'Synthetic fixture','synthetic fixture','USER',CURRENT_TIMESTAMP)",
                        user, account, "CREDIT".equals(direction)
                                ? UUID.fromString("00000000-0000-0000-0000-000000000002") : FOOD,
                        month.minusMonths(previous).atDay(5),
                        new BigDecimal("CREDIT".equals(direction) ? "1000.0000" : expense), direction, currency);
            }
        }
        UUID plan = UUID.randomUUID();
        jdbc.update("INSERT INTO " + S + "monthly_plan "
                + "(id,user_id,period_month,currency,status,expected_income_total,recommended_expense_budget,calculated_saving_capacity,calculation_method,method_version) "
                + "VALUES (?,?,?,?,'CLOSED',1000,900,100,'DETERMINISTIC','1')", plan, user, month.minusMonths(1).atDay(1), currency);
        jdbc.update("INSERT INTO " + S + "budget_allocation (monthly_plan_id,category_id,recommended_amount,recommendation_origin) "
                + "VALUES (?,?,900,'USER')", plan, FOOD);
    }

    private JsonNode generate(User user, String currency) throws Exception {
        return body(mvc.perform(post("/api/recommendations/current").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .param("currency", currency)).andExpect(status().isOk()).andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions feedback(User user, String id, String status) throws Exception {
        return mvc.perform(patch("/api/recommendations/{id}/status", id).header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    private int count(UUID user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "personalized_recommendation WHERE user_id=?", Integer.class, user);
    }
    private JsonNode body(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()); }
    private String bearer(User user) { return "Bearer " + user.token(); }
    private record User(UUID id, String token) { }
}
