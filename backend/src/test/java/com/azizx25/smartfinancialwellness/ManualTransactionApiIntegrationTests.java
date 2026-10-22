package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecordRepository;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.forecast.persistence.ForecastFinancialDataRepository;
import com.azizx25.smartfinancialwellness.salary.persistence.SalaryHistoryRepository;
import com.azizx25.smartfinancialwellness.score.persistence.FinancialScoreInputRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(PostgresTestConfiguration.class)
class ManualTransactionApiIntegrationTests {
    private static final String S = "smart_financial_wellness.";
    private static final UUID FOOD = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Tunis"));
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionRecordRepository transactions;
    @Autowired SalaryHistoryRepository salary;
    @Autowired FinancialScoreInputRepository scores;
    @Autowired ForecastFinancialDataRepository forecast;
    @Autowired CategoryRepository categories;
    @Autowired com.azizx25.smartfinancialwellness.category.MerchantRecognitionPersistence merchantPersistence;

    @Test
    void approvalViewsAreServerPagedOwnedAndMoveRowsInBothDirectionsWithoutDuplicatingThem() throws Exception {
        User owner = user(true), other = user(true);
        UUID tnd = account(owner, "TND"), eur = account(owner, "EUR");
        JsonNode pending = body(create(owner, request(tnd, UUID.randomUUID(), "8", TODAY, null)).andExpect(status().isCreated()));
        create(owner, request(tnd, UUID.randomUUID(), "9", TODAY, FOOD)).andExpect(status().isCreated());
        create(owner, request(eur, UUID.randomUUID(), "10", TODAY, null)).andExpect(status().isCreated());
        create(other, request(account(other, "TND"), UUID.randomUUID(), "99", TODAY, FOOD)).andExpect(status().isCreated());
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, owner.bearer())
                .param("accountId", tnd.toString()).param("approved", "false").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(pending.get("id").asText()))
                .andExpect(jsonPath("$.items[0].categoryApproved").value(false));
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, owner.bearer())
                .param("approved", "true")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].categoryApproved").value(true));
        mvc.perform(patch("/api/transactions/{id}/category", pending.get("id").asText())
                .header(HttpHeaders.AUTHORIZATION, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":\"" + FOOD + "\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, owner.bearer())
                .param("accountId", tnd.toString()).param("approved", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, owner.bearer())
                .param("approved", "true").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(patch("/api/transactions/{id}/category", pending.get("id").asText())
                .header(HttpHeaders.AUTHORIZATION, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":null}")).andExpect(status().isOk());
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, owner.bearer())
                .param("accountId", tnd.toString()).param("approved", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, owner.bearer()).param("approved", "anything"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, other.bearer()).param("accountId", tnd.toString()).param("approved", "true"))
                .andExpect(status().isNotFound());
    }

    @Test
    void automaticRuleCategoriesAwaitHumanApproval() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "TND");
        JsonNode credit = body(create(owner, request(account, UUID.randomUUID(), "100", TODAY, null)
                .replace("Manual fixture", "VIREMENT SALAIRE SYNTHETIQUE").replace("\"DEBIT\"", "\"CREDIT\""))
                .andExpect(status().isCreated()));
        mvc.perform(post("/api/categorization-runs").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categorizedCount").value(1));
        JsonNode ruled = body(mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, owner.bearer()).param("approved", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].categoryAssignmentSource").value("DETERMINISTIC_RULE"))
                .andExpect(jsonPath("$.items[0].categoryApproved").value(false)));
        mvc.perform(patch("/api/transactions/{id}/category", credit.get("id").asText()).header(HttpHeaders.AUTHORIZATION, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"" + ruled.get("items").get(0).get("categoryId").asText() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignmentSource").value("MANUAL"));
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, owner.bearer()).param("approved", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].categoryApproved").value(true));
    }

    @Test
    void anInFlightMerchantResultCannotOverwriteReviewOfAnEditedManualDescriptor() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "TND");
        JsonNode entry = body(create(owner, request(account, UUID.randomUUID(), "5", TODAY, null)).andExpect(status().isCreated()));
        var candidate = merchantPersistence.loadCandidates(owner.id(), 50).getFirst();
        UUID id = UUID.fromString(entry.get("id").asText());
        update(owner, id, entry.get("version").asInt(), "6", TODAY, null).andExpect(status().isOk());
        var result = new com.azizx25.smartfinancialwellness.category.infrastructure.MerchantRecognitionAiClient.Prediction(
                "merchant-1", "UNRECOGNIZED", null, null, null, null, null, null, null,
                true, true, false, "MODEL_ABSTAINED", null, List.of(), null, null, null, List.of());
        assertThat(merchantPersistence.persistAiBatch(owner.id(), Map.of("merchant-1", candidate), List.of(result))).isZero();
        assertThat(jdbc.queryForObject("SELECT reason_code FROM " + S + "categorization_review WHERE transaction_id=? AND resolved_at IS NULL", String.class, id))
                .isEqualTo("CATEGORY_REMOVED");
    }

    @Test
    void createsAndEditsRowsButDeleteIsUnsupportedAndLeavesFinancialDataUnchanged() throws Exception {
        User user = user(true);
        UUID account = account(user, "TND");
        JsonNode created = body(create(user, request(account, UUID.randomUUID(), "25.1250", TODAY, FOOD))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value("25.1250")));
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(created.get("sourceType").asText()).isEqualTo("MANUAL");
        summary(user, TODAY).andExpect(jsonPath("$.monthlyCashFlows[0].expenses").value(25.125));
        JsonNode edited = body(update(user, id, created.get("version").asInt(), "40.4321", TODAY, FOOD)
                .andExpect(status().isOk()));
        assertThat(edited.get("amount").asText()).isEqualTo("40.4321");
        summary(user, TODAY).andExpect(jsonPath("$.categorySpending[0].totalExpenses").value(40.4321));
        mvc.perform(delete("/api/transactions/{id}", id).param("version", edited.get("version").asText())
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists(HttpHeaders.ALLOW))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        summary(user, TODAY).andExpect(jsonPath("$.monthlyCashFlows[0].expenses").value(40.4321))
                .andExpect(jsonPath("$.categorySpending[0].totalExpenses").value(40.4321));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM " + S + "transaction_record WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "transaction_category_history WHERE transaction_id=?", Integer.class, id)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "audit_log_entry WHERE resource_id=? AND action IN "
                + "('MANUAL_TRANSACTION_CREATED','MANUAL_TRANSACTION_UPDATED','MANUAL_TRANSACTION_DELETED')", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_balance FROM " + S + "financial_account WHERE id=?", BigDecimal.class, account))
                .isEqualByComparingTo("100.0000");
        assertThat(transactions.findById(id)).isPresent();
    }

    @Test
    void legacyRemovedRowsCannotBeRestoredOrLinkedToNewGoalContributions() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "TND");
        JsonNode entry = body(create(owner, request(account, UUID.randomUUID(), "5", TODAY, null)).andExpect(status().isCreated()));
        UUID id = UUID.fromString(entry.get("id").asText());
        legacyTombstone(id);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + S + "transaction_record SET deleted_at=NULL WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID goal = UUID.randomUUID();
        jdbc.update("INSERT INTO " + S + "financial_goal(id,user_id,name,goal_type,currency,target_amount,initial_amount,priority,status) "
                + "VALUES(?,?, 'Protected goal','OTHER','TND',1000,0,'MEDIUM','ACTIVE')", goal, owner.id());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + S
                + "goal_contribution(financial_goal_id,transaction_id,source,amount,contribution_date) VALUES(?,?,'TRANSACTION',1,?)", goal, id, TODAY))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyIsConcurrentAndNeverResurrectsLegacyRemovedRows() throws Exception {
        User user = user(true);
        UUID account = account(user, "TND");
        String request = request(account, UUID.randomUUID(), "12.5000", TODAY, null);
        var first = CompletableFuture.supplyAsync(() -> concurrentCreate(user, request));
        var second = CompletableFuture.supplyAsync(() -> concurrentCreate(user, request));
        JsonNode one = first.get(20, TimeUnit.SECONDS), two = second.get(20, TimeUnit.SECONDS);
        assertThat(one.get("id").asText()).isEqualTo(two.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "transaction_record WHERE user_id=?", Integer.class, user.id())).isEqualTo(1);
        create(user, request.replace("12.5000", "13.0000")).andExpect(status().isConflict());
        legacyTombstone(UUID.fromString(one.get("id").asText()));
        create(user, request).andExpect(status().isConflict());
    }

    @Test
    void creditCreationAndDirectionCorrectionsKeepAmountsPositiveAndCategoryHistoryCoherent() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "TND");
        UUID salaryCategory = UUID.fromString("00000000-0000-0000-0000-000000000002");
        JsonNode entry = body(create(owner, request(account, UUID.randomUUID(), "3.5000", TODAY, salaryCategory)
                .replace("\"DEBIT\"", "\"CREDIT\"")).andExpect(status().isCreated()));
        summary(owner, TODAY).andExpect(jsonPath("$.monthlyCashFlows[0].income").value(3.5));
        UUID id = UUID.fromString(entry.get("id").asText());
        update(owner, id, entry.get("version").asInt(), "3.5000", TODAY, FOOD)
                .andExpect(status().isOk()).andExpect(jsonPath("$.direction").value("DEBIT"));
        summary(owner, TODAY).andExpect(jsonPath("$.monthlyCashFlows[0].income").value(0))
                .andExpect(jsonPath("$.monthlyCashFlows[0].expenses").value(3.5));
        assertThat(jdbc.queryForObject("SELECT amount FROM " + S + "transaction_record WHERE id=?", BigDecimal.class, id))
                .isEqualByComparingTo("3.5000");
    }

    @Test
    void authenticationOwnershipAndImportedSourceManagementAreEnforced() throws Exception {
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        User owner = user(true), other = user(true), pending = user(false);
        UUID account = account(owner, "TND");
        String request = request(account, UUID.randomUUID(), "10", TODAY, null);
        create(other, request).andExpect(status().isNotFound());
        create(pending, request).andExpect(status().isForbidden());
        JsonNode created = body(create(owner, request).andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asText());
        update(other, id, 0, "20", TODAY, null).andExpect(status().isNotFound());
        mvc.perform(delete("/api/transactions/{id}", id).param("version", "0").header(HttpHeaders.AUTHORIZATION, other.bearer()))
                .andExpect(status().isMethodNotAllowed());
        // Obtain a real FILE_IMPORT through preview/confirmation rather than changing provenance.
        String csv = "date;description;amount\n" + TODAY + ";Imported fixture;-2.5000\n";
        JsonNode preview = body(upload(owner, account, csv, "/api/imports/csv/preview").andExpect(status().isOk()));
        upload(owner, account, csv, "/api/imports/csv/" + preview.get("jobId").asText() + "/confirm").andExpect(status().isOk());
        UUID imported = jdbc.queryForObject("SELECT id FROM " + S + "transaction_record WHERE user_id=? AND source_type='FILE_IMPORT'", UUID.class, owner.id());
        JsonNode edited = body(update(owner, imported, 0, "20", TODAY, FOOD)
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceType").value("FILE_IMPORT")));
        assertThat(jdbc.queryForObject("SELECT source_details->>'amount' FROM " + S + "transaction_record WHERE id=?", String.class, imported))
                .isEqualTo("2.5000");
        assertThat(jdbc.queryForObject("SELECT source_file_id IS NOT NULL AND source_row_number>0 AND local_override FROM " + S
                + "transaction_record WHERE id=?", Boolean.class, imported)).isTrue();
        mvc.perform(patch("/api/transactions/{id}/category", imported).header(HttpHeaders.AUTHORIZATION, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"" + FOOD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignmentSource").value("MANUAL"));
        // Approval of an unchanged manual choice does not increment the row version.
        mvc.perform(delete("/api/transactions/{id}", imported).param("version", edited.get("version").asText()).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isMethodNotAllowed());
        upload(owner, account, csv, "/api/imports/csv/" + preview.get("jobId").asText() + "/confirm")
                .andExpect(status().isOk()).andExpect(jsonPath("$.importedCount").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "transaction_record WHERE id=? AND deleted_at IS NULL", Integer.class, imported)).isEqualTo(1);
    }

    @Test
    void validatesPrecisionDatesDirectionCategoryOwnershipAndUnknownFields() throws Exception {
        User owner = user(true), other = user(true);
        UUID account = account(owner, "TND");
        for (String amount : List.of("0", "-1", "1.12345", "1000000000000000")) {
            create(owner, request(account, UUID.randomUUID(), amount, TODAY, null)).andExpect(status().isBadRequest());
        }
        create(owner, request(account, UUID.randomUUID(), "1", TODAY.plusDays(1), null)).andExpect(status().isBadRequest());
        create(owner, request(account, UUID.randomUUID(), "1", TODAY, null).replace("\"DEBIT\"", "\"debit\""))
                .andExpect(status().isBadRequest());
        String valid = request(account, UUID.randomUUID(), "1", TODAY, null);
        create(owner, valid.replace("Manual fixture", "\u0301")).andExpect(status().isBadRequest());
        create(owner, valid.substring(0, valid.length() - 1) + ",\"currency\":\"EUR\"}").andExpect(status().isBadRequest());
        UUID income = UUID.fromString("00000000-0000-0000-0000-000000000002");
        create(owner, request(account, UUID.randomUUID(), "1", TODAY, income)).andExpect(status().isBadRequest());
        JsonNode foreignCategory = body(mvc.perform(post("/api/categories").header(HttpHeaders.AUTHORIZATION, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Private\",\"nature\":\"EXPENSE\",\"color\":\"#123458\"}"))
                .andExpect(status().isCreated()));
        create(owner, request(account, UUID.randomUUID(), "1", TODAY, UUID.fromString(foreignCategory.get("id").asText())))
                .andExpect(status().isNotFound());
        JsonNode exact = body(create(owner, request(account, UUID.randomUUID(), "999999999999999.1234", TODAY, FOOD))
                .andExpect(status().isCreated()));
        assertThat(exact.get("amount").asText()).isEqualTo("999999999999999.1234");
        mvc.perform(get("/api/transactions").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(jsonPath("$.items[0].amountExact").value("999999999999999.1234"));
    }

    @Test
    void staleUpdatesAndLinkedGoalContributionsCannotBeModifiedAndDeletionIsUnavailable() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "TND");
        JsonNode created = body(create(owner, request(account, UUID.randomUUID(), "10", TODAY, null)).andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asText());
        JsonNode changed = body(update(owner, id, created.get("version").asInt(), "20", TODAY, FOOD).andExpect(status().isOk()));
        update(owner, id, created.get("version").asInt(), "99", TODAY, null).andExpect(status().isConflict());
        UUID goal = UUID.randomUUID();
        jdbc.update("INSERT INTO " + S + "financial_goal(id,user_id,name,goal_type,currency,target_amount,initial_amount,priority,status) "
                + "VALUES(?,?, 'Protected goal','OTHER','TND',1000,0,'MEDIUM','ACTIVE')", goal, owner.id());
        jdbc.update("INSERT INTO " + S + "goal_contribution(financial_goal_id,transaction_id,source,amount,contribution_date) VALUES(?,?,'TRANSACTION',1,?)",
                goal, id, TODAY);
        update(owner, id, changed.get("version").asInt(), "99", TODAY, null).andExpect(status().isConflict());
        mvc.perform(delete("/api/transactions/{id}", id).param("version", changed.get("version").asText())
                .header(HttpHeaders.AUTHORIZATION, owner.bearer())).andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("SELECT amount FROM " + S + "transaction_record WHERE id=?", BigDecimal.class, id)).isEqualByComparingTo("20");
    }

    @Test
    void legacyRemovedRowsStayExcludedFromHistoryAndAnalyticsWithoutRestoringThem() throws Exception {
        User owner = user(true);
        UUID tnd = account(owner, "TND"), eur = account(owner, "EUR");
        LocalDate historyDate = YearMonth.from(TODAY).minusMonths(1).atDay(5);
        JsonNode entry = body(create(owner, request(tnd, UUID.randomUUID(), "80", historyDate, FOOD)).andExpect(status().isCreated()));
        create(owner, request(eur, UUID.randomUUID(), "12", historyDate, FOOD)).andExpect(status().isCreated());
        UUID id = UUID.fromString(entry.get("id").asText());
        LocalDate from = historyDate.withDayOfMonth(1), to = from.plusMonths(1);
        assertThat(salary.summarizeEligibleExpenseSpending(owner.id(), "TND", from, to)).hasSize(1);
        assertThat(scores.findEligibleTransactions(owner.id(), "TND", from, to)).hasSize(1);
        assertThat(forecast.findEligibleCashFlows(owner.id(), List.of(tnd), "TND", from, to)).hasSize(1);
        legacyTombstone(id);
        assertThat(salary.summarizeEligibleExpenseSpending(owner.id(), "TND", from, to)).isEmpty();
        assertThat(scores.findEligibleTransactions(owner.id(), "TND", from, to)).isEmpty();
        assertThat(forecast.findEligibleCashFlows(owner.id(), List.of(tnd), "TND", from, to)).isEmpty();
        assertThat(categories.summarize(owner.id(), from, to)).hasSize(1).allSatisfy(row -> assertThat(row.getCurrency()).isEqualTo("EUR"));
        mvc.perform(get("/api/categorization-reviews").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        summary(owner, historyDate).andExpect(jsonPath("$.monthlyCashFlows[0].currency").value("EUR"));
        summary(owner, TODAY).andExpect(jsonPath("$.monthlyCashFlows").isEmpty());
    }

    private void legacyTombstone(UUID id) {
        // Isolated test fixture representing a row removed before transaction deletion was retired.
        jdbc.update("UPDATE " + S + "transaction_record SET deleted_at=GREATEST(CURRENT_TIMESTAMP,created_at) WHERE id=?", id);
    }

    private JsonNode concurrentCreate(User user, String request) {
        try { return body(create(user, request).andExpect(status().isCreated())); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private ResultActions create(User user, String request) throws Exception {
        return mvc.perform(post("/api/transactions").header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(request));
    }
    private ResultActions update(User user, UUID id, int version, String amount, LocalDate date, UUID category) throws Exception {
        return mvc.perform(put("/api/transactions/{id}", id).header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "version", version, "bookingDate", date.toString(), "description", "Manual fixture edited",
                        "amount", amount, "direction", "DEBIT", "categoryId", category == null ? "00000000-0000-0000-0000-000000000001" : category.toString()))));
    }
    private String request(UUID account, UUID key, String amount, LocalDate date, UUID category) throws Exception {
        return json.writeValueAsString(Map.of("accountId", account, "clientIdempotencyKey", key,
                "bookingDate", date.toString(), "description", "Manual fixture", "amount", amount, "direction", "DEBIT",
                "categoryId", category == null ? "00000000-0000-0000-0000-000000000001" : category.toString()));
    }
    private JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    private ResultActions summary(User user, LocalDate date) throws Exception {
        return mvc.perform(get("/api/dashboard/summary").param("month", YearMonth.from(date).toString()).header(HttpHeaders.AUTHORIZATION, user.bearer()));
    }
    private UUID account(User user, String currency) throws Exception {
        return UUID.fromString(body(mvc.perform(post("/api/imports/accounts").header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Personal\",\"currency\":\"" + currency + "\",\"currentBalance\":100}"))
                .andExpect(status().isOk())).get("id").asText());
    }
    private ResultActions upload(User user, UUID account, String csv, String path) throws Exception {
        return mvc.perform(multipart(path)
                .file(new MockMultipartFile("file", "synthetic.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("request", "", "application/json", ("{\"accountId\":\"" + account + "\"}").getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, user.bearer()));
    }
    private User user(boolean active) throws Exception {
        JsonNode registered = body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"manual-" + UUID.randomUUID() + "@example.test\",\"password\":\"TestOnlyPass123!\",\"firstName\":\"Manual\",\"lastName\":\"Tester\",\"displayName\":\"Manual Tester\"}"))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(registered.get("user").get("id").asText());
        if (active) {
            jdbc.update("UPDATE " + S + "app_user SET status='ACTIVE' WHERE id=?", id);
            jdbc.update("UPDATE " + S + "authentication_identity SET status='ACTIVE', verified_at=CURRENT_TIMESTAMP WHERE user_id=?", id);
        }
        return new User(id, "Bearer " + registered.get("accessToken").asText());
    }
    private record User(UUID id, String bearer) { }
}
