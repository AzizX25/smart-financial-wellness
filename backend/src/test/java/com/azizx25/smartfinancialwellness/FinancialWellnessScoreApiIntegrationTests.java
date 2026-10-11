package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class,
        FinancialWellnessScoreApiIntegrationTests.ScoreClockConfiguration.class})
class FinancialWellnessScoreApiIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final Instant NOW = Instant.now();
    private static final LocalDate AS_OF = LocalDate.ofInstant(NOW, ZoneId.of("Africa/Tunis"));
    private static final YearMonth CURRENT_MONTH = YearMonth.from(AS_OF);
    private static final UUID SALARY = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FOOD = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID EDUCATION = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final UUID TRANSFER_IN = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID TRANSFER_OUT = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void persistsAnImmutableIdempotentVersionedScoreAndHidesForeignOwnership() throws Exception {
        mockMvc.perform(post("/api/financial-scores/current").param("currency", "TND"))
                .andExpect(status().isUnauthorized());
        Registration owner = registerAndActivate();
        UUID accountId = createManualAccount(owner.userId(), "TND");
        createThreeMonthHistory(owner.userId(), accountId, "TND");
        createSalaryPlan(owner.userId(), "TND", CURRENT_MONTH.minusMonths(2), "800.0000");

        MvcResult first = calculate(owner, "TND")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.score").value(100.0))
                .andExpect(jsonPath("$.bandCode").value("SOLIDE"))
                .andExpect(jsonPath("$.bandLabel").value("Solide"))
                .andExpect(jsonPath("$.calculationVersion")
                        .value("financial-wellness-score-v1"))
                .andExpect(jsonPath("$.asOfDate").value(AS_OF.toString()))
                .andExpect(jsonPath("$.historyStart").value(CURRENT_MONTH.minusMonths(6).atDay(1).toString()))
                .andExpect(jsonPath("$.historyEnd").value(CURRENT_MONTH.minusMonths(1).atEndOfMonth().toString()))
                .andExpect(jsonPath("$.timeZone").value("Africa/Tunis"))
                .andExpect(jsonPath("$.availableBaseWeight").value(55))
                .andExpect(jsonPath("$.factors.length()").value(6))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.inputFingerprint").doesNotExist())
                .andReturn();
        JsonNode body = objectMapper.readTree(first.getResponse().getContentAsString());
        UUID scoreId = UUID.fromString(body.get("id").asText());
        assertThat(factor(body, "ESSENTIAL_EXPENSE_BURDEN").get("available").asBoolean()).isFalse();
        assertThat(factor(body, "BALANCE_RESILIENCE").get("available").asBoolean()).isFalse();

        calculate(owner, "TND").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(scoreId.toString()));
        assertThat(countScores(owner.userId())).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + SCHEMA
                        + "evaluation_dimension WHERE wellness_evaluation_id = ?", Integer.class, scoreId))
                .isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("SELECT input_fingerprint FROM " + SCHEMA
                        + "wellness_evaluation WHERE id = ?", String.class, scoreId))
                .matches("[0-9a-f]{64}");

        mockMvc.perform(get("/api/financial-scores")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .param("currency", "TND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(scoreId.toString()));
        mockMvc.perform(get("/api/financial-scores/{id}", scoreId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factors.length()").value(6));

        Registration other = registerAndActivate();
        mockMvc.perform(get("/api/financial-scores/{id}", scoreId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FINANCIAL_SCORE_NOT_FOUND"));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "wellness_evaluation SET global_score = 1 WHERE id = ?", scoreId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM " + SCHEMA + "evaluation_dimension WHERE wellness_evaluation_id = ?", scoreId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void usesOnlyEligibleCompletedOwnedSameCurrencyTransactions() throws Exception {
        Registration owner = registerAndActivate();
        UUID manual = createManualAccount(owner.userId(), "TND");
        for (int monthsAgo = 4; monthsAgo >= 2; monthsAgo--) {
            insertManualTransaction(owner.userId(), manual, SALARY, historyDate(monthsAgo, 5),
                    "1000.0000", "CREDIT");
        }
        insertManualTransaction(owner.userId(), manual, FOOD, historyDate(2, 6),
                "100.0000", "DEBIT");

        BankFixture bank = createBankFixture(owner.userId(), "TND");
        insertBankTransaction(owner.userId(), bank, FOOD, historyDate(2, 7),
                "200.0000", "DEBIT", "POSTED");
        insertBankTransaction(owner.userId(), bank, FOOD, historyDate(2, 8),
                "300.0000", "DEBIT", "PENDING");
        UUID outgoing = insertManualTransaction(owner.userId(), manual, TRANSFER_OUT,
                historyDate(2, 9), "400.0000", "DEBIT");
        UUID incoming = insertManualTransaction(owner.userId(), bank.accountId(), TRANSFER_IN,
                historyDate(2, 9), "400.0000", "CREDIT");
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "internal_transfer "
                        + "(outgoing_transaction_id,incoming_transaction_id,detection_method,confirmed_at) "
                        + "VALUES (?,?,'USER',?)", outgoing, incoming, Timestamp.from(NOW));
        insertManualTransaction(owner.userId(), manual, FOOD, CURRENT_MONTH.atDay(1),
                "500.0000", "DEBIT");
        insertManualTransaction(owner.userId(), manual, FOOD, CURRENT_MONTH.plusMonths(1).atDay(1),
                "600.0000", "DEBIT");

        Registration other = registerAndActivate();
        UUID otherAccount = createManualAccount(other.userId(), "TND");
        insertManualTransaction(other.userId(), otherAccount, FOOD, historyDate(2, 1),
                "700.0000", "DEBIT");
        UUID euroAccount = createManualAccount(owner.userId(), "EUR");
        insertManualTransaction(owner.userId(), euroAccount, FOOD, historyDate(2, 1),
                "800.0000", "DEBIT");
        createSalaryPlan(owner.userId(), "TND", CURRENT_MONTH.minusMonths(2), "300.0000");

        JsonNode result = body(calculate(owner, "TND").andExpect(status().isOk()).andReturn());
        JsonNode savings = factor(result, "SAVINGS_RATE");
        assertThat(savings.get("rawValue").decimalValue()).isEqualByComparingTo("0.9000");
        assertThat(result.get("score").decimalValue()).isEqualByComparingTo("100.0000");
    }

    @Test
    void categoryCorrectionAndChangedAmountsProduceNewSnapshotsWithoutChangingPastOnes() throws Exception {
        Registration owner = registerAndActivate();
        UUID account = createManualAccount(owner.userId(), "TND");
        createThreeMonthHistory(owner.userId(), account, "TND");
        UUID corrected = insertManualTransaction(owner.userId(), account, FOOD,
                historyDate(2, 20), "50.0000", "DEBIT");
        createSalaryPlan(owner.userId(), "TND", CURRENT_MONTH.minusMonths(2), "850.0000");

        JsonNode first = body(calculate(owner, "TND").andReturn());
        String firstFingerprint = fingerprint(UUID.fromString(first.get("id").asText()));
        BigDecimal firstSavingsScore = factor(first, "SAVINGS_RATE").get("score").decimalValue();

        mockMvc.perform(patch("/api/transactions/{id}/category", corrected)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + EDUCATION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(EDUCATION.toString()));
        JsonNode recategorized = body(calculate(owner, "TND").andReturn());
        assertThat(recategorized.get("id").asText()).isNotEqualTo(first.get("id").asText());
        assertThat(fingerprint(UUID.fromString(recategorized.get("id").asText())))
                .isNotEqualTo(firstFingerprint);
        assertThat(factor(recategorized, "SAVINGS_RATE").get("score").decimalValue())
                .isEqualByComparingTo(firstSavingsScore);

        jdbcTemplate.update("UPDATE " + SCHEMA + "transaction_record SET amount = 250.0000 "
                + "WHERE id = ?", corrected);
        JsonNode changed = body(calculate(owner, "TND").andReturn());
        assertThat(changed.get("id").asText()).isNotEqualTo(recategorized.get("id").asText());
        assertThat(factor(changed, "SAVINGS_RATE").get("score").decimalValue())
                .isLessThan(firstSavingsScore);
        assertThat(countScores(owner.userId())).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT global_score FROM " + SCHEMA
                        + "wellness_evaluation WHERE id = ?", BigDecimal.class,
                UUID.fromString(first.get("id").asText()))).isEqualByComparingTo(first.get("score").asText());
    }

    @Test
    void insufficientHistoryReturnsExplicitNonErrorAndPersistsNothing() throws Exception {
        Registration owner = registerAndActivate();
        UUID account = createManualAccount(owner.userId(), "TND");
        for (int monthsAgo = 3; monthsAgo >= 2; monthsAgo--) {
            insertManualTransaction(owner.userId(), account, SALARY, historyDate(monthsAgo, 5),
                    "1000.0000", "CREDIT");
        }
        createSalaryPlan(owner.userId(), "TND", CURRENT_MONTH.minusMonths(2), "500.0000");

        calculate(owner, "TND")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.score").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.factors.length()").value(6));
        assertThat(countScores(owner.userId())).isZero();
    }

    @Test
    void flywayV21AndHibernateMappingsLoadCleanly() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + SCHEMA
                        + "flyway_schema_history WHERE version = '21' AND success", Integer.class))
                .isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'smart_financial_wellness' "
                        + "AND table_name = 'wellness_evaluation' "
                        + "AND column_name IN ('as_of_date','time_zone','available_base_weight','input_fingerprint')",
                Integer.class)).isEqualTo(4);
    }

    private org.springframework.test.web.servlet.ResultActions calculate(Registration user, String currency)
            throws Exception {
        return mockMvc.perform(post("/api/financial-scores/current")
                .header(HttpHeaders.AUTHORIZATION, bearer(user.token())).param("currency", currency));
    }

    private Registration registerAndActivate() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"score-%s@example.test","password":"StrongPass123!",
                                "firstName":"Score","lastName":"Tester","displayName":"Score Tester"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode payload = body(result);
        UUID userId = UUID.fromString(payload.get("user").get("id").asText());
        jdbcTemplate.update("UPDATE " + SCHEMA + "app_user SET status = 'ACTIVE' WHERE id = ?", userId);
        jdbcTemplate.update("UPDATE " + SCHEMA + "authentication_identity "
                + "SET status = 'ACTIVE', verified_at = ? WHERE user_id = ?", Timestamp.from(NOW), userId);
        return new Registration(userId, payload.get("accessToken").asText());
    }

    private UUID createManualAccount(UUID userId, String currency) {
        UUID account = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "financial_account "
                        + "(id,user_id,account_origin,name,account_type,currency,status) "
                        + "VALUES (?,?,'MANUAL','Score account','CHECKING',?,'ACTIVE')",
                account, userId, currency);
        return account;
    }

    private void createThreeMonthHistory(UUID userId, UUID account, String currency) {
        for (int monthsAgo = 4; monthsAgo >= 2; monthsAgo--) {
            insertManualTransaction(userId, account, SALARY, historyDate(monthsAgo, 5),
                    "1000.0000", "CREDIT");
            insertManualTransaction(userId, account, FOOD, historyDate(monthsAgo, 10),
                    "800.0000", "DEBIT");
        }
    }

    private UUID insertManualTransaction(
            UUID userId, UUID account, UUID category, LocalDate date,
            String amount, String direction) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id,user_id,financial_account_id,category_id,source_type,booking_date,amount,"
                        + "direction,currency,raw_description,normalized_description,category_source,categorized_at) "
                        + "SELECT ?,?,?,?,'MANUAL',?,?,?,account.currency,'Score fixture','score fixture','USER',? "
                        + "FROM " + SCHEMA + "financial_account account WHERE account.id = ?",
                id, userId, account, category, date, new BigDecimal(amount), direction,
                Timestamp.from(NOW), account);
        return id;
    }

    private void createSalaryPlan(UUID userId, String currency, YearMonth month, String budget) {
        UUID plan = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "monthly_plan "
                        + "(id,user_id,period_month,currency,status,expected_income_total,"
                        + "recommended_expense_budget,calculated_saving_capacity,calculation_method,method_version) "
                        + "VALUES (?,?,?,?,'CLOSED',1000.0000,?,200.0000,'DETERMINISTIC','1')",
                plan, userId, month.atDay(1), currency, new BigDecimal(budget));
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "budget_allocation "
                        + "(monthly_plan_id,category_id,recommended_amount,recommendation_origin) "
                        + "VALUES (?,?,?,'USER')", plan, FOOD, new BigDecimal(budget));
    }

    private BankFixture createBankFixture(UUID userId, String currency) {
        UUID connection = UUID.randomUUID();
        UUID authorization = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "bank_connection "
                        + "(id,user_id,provider_code,external_customer_id,status,sync_status,last_sync_attempted_at) "
                        + "VALUES (?,?,'MOCK_BANK',?,'ACTIVE','SUCCEEDED',?)",
                connection, userId, "score-customer-" + connection, Timestamp.from(NOW));
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "bank_connection_authorization "
                        + "(id,bank_connection_id,status,secret_reference,granted_scopes,consented_at,expires_at) "
                        + "VALUES (?,?,'ACTIVE',?,ARRAY['ACCOUNTS_READ','BALANCES_READ','TRANSACTIONS_READ'],?,?)",
                authorization, connection, "test-vault://score/" + authorization,
                Timestamp.from(NOW.minusSeconds(3600)),
                Timestamp.from(NOW.plusSeconds(86400)));
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "sync_job "
                        + "(id,bank_connection_id,authorization_id,status,created_at,started_at,completed_at) "
                        + "VALUES (?,?,?,'SUCCEEDED',?,?,?)", job, connection, authorization,
                Timestamp.from(NOW.minusSeconds(120)), Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW));
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "financial_account "
                        + "(id,user_id,bank_connection_id,account_origin,external_account_id,name,account_type,"
                        + "currency,status,provider_code,provider_account_status,first_synchronized_at,last_synchronized_at) "
                        + "VALUES (?,?,?,'BANK_CONNECTION',?,'Score bank','CHECKING',?,'ACTIVE',"
                        + "'MOCK_BANK','ACTIVE',?,?)", account, userId, connection,
                "score-account-" + account, currency, Timestamp.from(NOW), Timestamp.from(NOW));
        return new BankFixture(account, job);
    }

    private void insertBankTransaction(
            UUID userId, BankFixture bank, UUID category, LocalDate date,
            String amount, String direction, String providerStatus) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id,user_id,financial_account_id,category_id,source_type,sync_job_id,"
                        + "external_transaction_id,booking_date,amount,direction,currency,raw_description,"
                        + "normalized_description,category_source,categorized_at,provider_status,"
                        + "first_imported_at,last_synchronized_at) "
                        + "SELECT ?,?,?,?,'BANK_SYNC',?,?,?, ?,?,account.currency,'Score bank fixture',"
                        + "'score bank fixture','USER',?,?,?,? FROM " + SCHEMA
                        + "financial_account account WHERE account.id = ?",
                id, userId, bank.accountId(), category, bank.jobId(), "score-transaction-" + id,
                date, new BigDecimal(amount), direction, Timestamp.from(NOW), providerStatus,
                Timestamp.from(NOW), Timestamp.from(NOW), bank.accountId());
    }

    private int countScores(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + SCHEMA
                        + "wellness_evaluation WHERE user_id = ? "
                        + "AND method_version = 'financial-wellness-score-v1'",
                Integer.class, userId);
    }

    private String fingerprint(UUID scoreId) {
        return jdbcTemplate.queryForObject("SELECT input_fingerprint FROM " + SCHEMA
                + "wellness_evaluation WHERE id = ?", String.class, scoreId);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode factor(JsonNode response, String code) {
        for (JsonNode factor : response.get("factors")) {
            if (code.equals(factor.get("code").asText())) return factor;
        }
        throw new AssertionError("Missing factor " + code);
    }

    private String bearer(String token) { return "Bearer " + token; }

    private LocalDate historyDate(int monthsAgo, int day) {
        return CURRENT_MONTH.minusMonths(monthsAgo).atDay(day);
    }

    private record Registration(UUID userId, String token) {
    }

    private record BankFixture(UUID accountId, UUID jobId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScoreClockConfiguration {
        @Bean
        @Primary
        Clock scoreClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
