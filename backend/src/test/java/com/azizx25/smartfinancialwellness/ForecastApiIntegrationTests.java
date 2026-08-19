package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.azizx25.smartfinancialwellness.forecast.ForecastFeatureAssembler.ForecastFeatures;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiClient;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiClient.ForecastAiPrediction;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiExceptions.Unavailable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, ForecastApiIntegrationTests.ForecastTestConfiguration.class})
class ForecastApiIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final UUID UNCATEGORIZED =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.now();
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StubForecastAiClient aiClient;

    @BeforeEach
    void resetClient() {
        aiClient.mode.set(Mode.SUCCESS);
        aiClient.calls.set(0);
        aiClient.lastFeatures.set(null);
    }

    @Test
    void appliesMlOnDay25PersistsOnceAuditsOnceAndSimulatesWithoutPersistence() throws Exception {
        mockMvc.perform(post("/api/forecasts/current")
                        .param("currency", "TND").param("timezone", "Africa/Tunis"))
                .andExpect(status().isUnauthorized());
        Registration user = registerAndActivate();
        createCompleteFixture(user.userId(), "TND", 6);

        MvcResult generated = generate(user, "TND")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.cutoffDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.startingBalance").value(1000.0))
                .andExpect(jsonPath("$.deterministicRemainingNetFlow").value(100.0))
                .andExpect(jsonPath("$.deterministicPredictedEndBalance").value(1100.0))
                .andExpect(jsonPath("$.mlStatus").value("APPLIED"))
                .andExpect(jsonPath("$.mlPredictedResidual").value(12.3456))
                .andExpect(jsonPath("$.finalPredictedEndBalance").value(1112.3456))
                .andExpect(jsonPath("$.experimental").value(true))
                .andExpect(jsonPath("$.syntheticTrainingData").value(true))
                .andExpect(jsonPath("$.guaranteed").value(false))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.canonicalInputFingerprint").doesNotExist())
                .andReturn();
        UUID forecastId = UUID.fromString(objectMapper.readTree(
                generated.getResponse().getContentAsString()).get("id").asText());

        assertThat(aiClient.lastFeatures.get().cutoffDay()).isEqualTo(TODAY.getDayOfMonth());
        assertThat(aiClient.calls.get()).isOne();
        generate(user, "TND").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(forecastId.toString()));
        assertThat(count("forecast_result", "user_id", user.userId())).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "audit_log_entry "
                        + "WHERE actor_id = ? AND action = 'FORECAST_GENERATED'",
                Integer.class, user.userId())).isOne();

        mockMvc.perform(get("/api/forecasts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .param("currency", "TND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(forecastId.toString()));
        mockMvc.perform(get("/api/forecasts/{id}", forecastId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalPredictedEndBalance").value(1112.3456));

        mockMvc.perform(post("/api/forecasts/{id}/simulations", forecastId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"additionalIncome\":\"50.0000\",\"additionalExpense\":\"20.0000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basePredictedEndBalance").value(1112.3456))
                .andExpect(jsonPath("$.simulatedEndBalance").value(1142.3456))
                .andExpect(jsonPath("$.guaranteed").value(false));
        assertThat(count("forecast_result", "user_id", user.userId())).isOne();
        assertThat(aiClient.calls.get()).isEqualTo(2);
    }

    @Test
    void fallsBackWithout500ForUnsupportedHistoryUnavailableAndInvalidModelResponses() throws Exception {
        Registration unsupported = registerAndActivate();
        createCompleteFixture(unsupported.userId(), "EUR", 6);
        generate(unsupported, "EUR").andExpect(status().isOk())
                .andExpect(jsonPath("$.mlStatus").value("UNSUPPORTED_CURRENCY"))
                .andExpect(jsonPath("$.finalPredictedEndBalance")
                        .value(org.hamcrest.Matchers.equalTo(1100.0)));

        Registration limited = registerAndActivate();
        createCompleteFixture(limited.userId(), "TND", 2);
        generate(limited, "TND").andExpect(status().isOk())
                .andExpect(jsonPath("$.mlStatus").value("INSUFFICIENT_HISTORY"));

        Registration unavailable = registerAndActivate();
        createCompleteFixture(unavailable.userId(), "TND", 6);
        aiClient.mode.set(Mode.UNAVAILABLE);
        generate(unavailable, "TND").andExpect(status().isOk())
                .andExpect(jsonPath("$.mlStatus").value("SERVICE_UNAVAILABLE"));

        Registration mismatch = registerAndActivate();
        createCompleteFixture(mismatch.userId(), "TND", 6);
        aiClient.mode.set(Mode.VERSION_MISMATCH);
        generate(mismatch, "TND").andExpect(status().isOk())
                .andExpect(jsonPath("$.mlStatus").value("MODEL_VERSION_MISMATCH"));

        Registration invalid = registerAndActivate();
        createCompleteFixture(invalid.userId(), "TND", 6);
        aiClient.mode.set(Mode.INVALID_FLAGS);
        generate(invalid, "TND").andExpect(status().isOk())
                .andExpect(jsonPath("$.mlStatus").value("INVALID_RESPONSE"));
    }

    @Test
    void returnsExplicitUnavailableStatesAndHidesForeignForecasts() throws Exception {
        Registration empty = registerAndActivate();
        generate(empty, "TND").andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("NO_ELIGIBLE_ACCOUNTS"));

        Registration owner = registerAndActivate();
        createCompleteFixture(owner.userId(), "TND", 6);
        UUID id = UUID.fromString(objectMapper.readTree(generate(owner, "TND").andReturn()
                .getResponse().getContentAsString()).get("id").asText());
        Registration other = registerAndActivate();
        mockMvc.perform(get("/api/forecasts/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORECAST_NOT_FOUND"));
        mockMvc.perform(post("/api/forecasts/{id}/simulations", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void databaseRejectsMutationOfAProducedForecast() throws Exception {
        Registration user = registerAndActivate();
        createCompleteFixture(user.userId(), "TND", 6);
        UUID id = UUID.fromString(objectMapper.readTree(generate(user, "TND").andReturn()
                .getResponse().getContentAsString()).get("id").asText());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "forecast_result SET starting_balance = 0 WHERE id = ?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM " + SCHEMA + "forecast_result WHERE id = ?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private org.springframework.test.web.servlet.ResultActions generate(Registration user, String currency)
            throws Exception {
        return mockMvc.perform(post("/api/forecasts/current")
                .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                .param("currency", currency)
                .param("timezone", "Africa/Tunis"));
    }

    private Registration registerAndActivate() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"forecast-%s@example.test","password":"StrongPass123!",
                                "firstName":"Forecast","lastName":"Tester","displayName":"Forecast Tester"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(body.get("user").get("id").asText());
        jdbcTemplate.update("UPDATE " + SCHEMA + "app_user SET status = 'ACTIVE' WHERE id = ?", userId);
        jdbcTemplate.update("UPDATE " + SCHEMA + "authentication_identity "
                + "SET status = 'ACTIVE', verified_at = CURRENT_TIMESTAMP WHERE user_id = ?", userId);
        return new Registration(userId, body.get("accessToken").asText());
    }

    private void createCompleteFixture(UUID userId, String currency, int historyMonths) {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "financial_account "
                        + "(id,user_id,account_origin,name,account_type,currency,status) "
                        + "VALUES (?,?,'MANUAL','Forecast','CHECKING',?,'ACTIVE')",
                accountId, userId, currency);
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "balance_snapshot "
                        + "(financial_account_id,ledger_balance,available_balance,currency,source_type,observed_at) "
                        + "VALUES (?,1000.0000,1000.0000,?,'MANUAL',?)",
                accountId, currency, Timestamp.from(TODAY.atTime(8, 0).toInstant(ZoneOffset.UTC)));
        for (int offset = historyMonths; offset >= 1; offset--) {
            LocalDate date = TODAY.withDayOfMonth(1).minusMonths(offset).withDayOfMonth(26);
            insertTransaction(userId, accountId, currency, date, "100.0000", "CREDIT");
        }
        insertTransaction(userId, accountId, currency, TODAY.minusDays(1),
                "10.0000", "DEBIT");
    }

    private void insertTransaction(
            UUID userId, UUID accountId, String currency, LocalDate date,
            String amount, String direction) {
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id,user_id,financial_account_id,category_id,source_type,booking_date,amount,"
                        + "direction,currency,raw_description,normalized_description,category_source) "
                        + "VALUES (?,?,?,?, 'MANUAL',?,?,?,?, 'Forecast fixture','forecast fixture','UNCATEGORIZED')",
                UUID.randomUUID(), userId, accountId, UNCATEGORIZED, date,
                new BigDecimal(amount), direction, currency);
    }

    private int count(String table, String column, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + table + " WHERE " + column + " = ?",
                Integer.class, id);
    }

    private String bearer(String token) { return "Bearer " + token; }

    private record Registration(UUID userId, String token) {
    }

    enum Mode { SUCCESS, UNAVAILABLE, VERSION_MISMATCH, INVALID_FLAGS }

    static final class StubForecastAiClient implements ForecastAiClient {
        final AtomicReference<Mode> mode = new AtomicReference<>(Mode.SUCCESS);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<ForecastFeatures> lastFeatures = new AtomicReference<>();

        @Override
        public ForecastAiPrediction predict(String currency, ForecastFeatures features) {
            calls.incrementAndGet();
            lastFeatures.set(features);
            if (mode.get() == Mode.UNAVAILABLE) {
                throw new Unavailable(new java.net.ConnectException("test"));
            }
            return new ForecastAiPrediction(
                    "forecast-huber-synthetic-tnd",
                    mode.get() == Mode.VERSION_MISMATCH ? "2.0.0" : "1.0.0",
                    "forecast-residual-features-v1", currency, "12.3456", true,
                    true, mode.get() == Mode.INVALID_FLAGS);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ForecastTestConfiguration {
        @Bean @Primary Clock forecastClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean @Primary StubForecastAiClient stubForecastAiClient() {
            return new StubForecastAiClient();
        }
    }
}
