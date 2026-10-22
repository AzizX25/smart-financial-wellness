package com.azizx25.smartfinancialwellness;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class CsvImportApiIntegrationTests {
    private static final String S = "smart_financial_wellness.";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Tunis"));
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void previewsWithoutTransactionsThenImportsIntoCanonicalAnalyticsWithoutInventingBalance() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "Relevé", "TND");
        String csv = csv("-25.1250", "3500.0000");
        UUID job = job(upload(owner, account, csv, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.canImport").value(true)).andReturn());
        assertThat(transactionCount(owner)).isZero();
        confirm(owner, account, job, csv, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(2));
        assertThat(transactionCount(owner)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_balance FROM " + S + "financial_account WHERE id=?",
                java.math.BigDecimal.class, account)).isEqualByComparingTo("4200.0000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "transaction_record WHERE user_id=? "
                + "AND source_type='FILE_IMPORT' AND amount>0 AND provider_status IS NULL "
                + "AND first_imported_at IS NULL AND last_synchronized_at IS NULL "
                + "AND import_job_id=? AND source_row_number IN (2,3)", Integer.class, owner.id(), job)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT user_confirmed FROM " + S + "import_mapping WHERE import_job_id=?", Boolean.class, job)).isTrue();
        assertThat(jdbc.queryForObject("SELECT storage_reference FROM " + S + "source_file WHERE user_id=?", String.class, owner.id()))
                .startsWith("not-retained:sha256:");
        assertThat(jdbc.queryForObject("SELECT source_type FROM " + S + "balance_snapshot WHERE financial_account_id=?", String.class, account))
                .isEqualTo("MANUAL");
        mvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].accountOrigin").value("MANUAL"));
        mvc.perform(get("/api/transactions").param("accountId", account.toString()).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/dashboard/summary").param("month", TODAY.toString().substring(0, 7))
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.monthlyCashFlows[0].expenses").value(25.125))
                .andExpect(jsonPath("$.monthlyCashFlows[0].income").value(3500));
    }

    @Test
    void repeatedImportsAndAccountsAreIdempotentWhileIdenticalContentRowsRemainDistinct() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "Relevé", "TND");
        assertThat(account(owner, "relevé", "TND")).isEqualTo(account);
        String csv = csv("-25", "-25");
        UUID job = job(upload(owner, account, csv, null).andReturn());
        confirm(owner, account, job, csv, null).andExpect(status().isOk());
        UUID transactionId = jdbc.queryForObject("SELECT id FROM " + S
                + "transaction_record WHERE user_id=? AND source_row_number=2", UUID.class, owner.id());
        mvc.perform(patch("/api/transactions/{id}/category", transactionId).header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"00000000-0000-0000-0000-000000000005\"}"))
                .andExpect(status().isOk());
        confirm(owner, account, job, csv, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyImported").value(true)).andExpect(jsonPath("$.importedCount").value(0))
                .andExpect(jsonPath("$.duplicateCount").value(2));
        upload(owner, account, csv, null).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyImported").value(true));
        assertThat(transactionCount(owner)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT category_source FROM " + S
                + "transaction_record WHERE id=?", String.class, transactionId)).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "source_file WHERE user_id=?", Integer.class, owner.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "import_job WHERE user_id=?", Integer.class, owner.id())).isEqualTo(1);
        User other = user(true);
        UUID otherAccount = account(other, "Relevé", "TND");
        UUID otherJob = job(upload(other, otherAccount, csv, null).andReturn());
        confirm(other, otherAccount, otherJob, csv, null).andExpect(status().isOk());
        assertThat(transactionCount(other)).isEqualTo(2);
    }

    @Test
    void enforcesAuthenticationActiveStatusOwnershipAndStrictRequestFields() throws Exception {
        mvc.perform(post("/api/imports/accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        User owner = user(true);
        UUID account = account(owner, "Relevé", "TND");
        UUID job = job(upload(owner, account, csv("-2", "3"), null).andReturn());
        User other = user(true);
        upload(other, account, csv("-2", "3"), null).andExpect(status().isNotFound());
        UUID otherAccount = account(other, "Relevé", "TND");
        confirm(other, otherAccount, job, csv("-2", "3"), null).andExpect(status().isNotFound());
        upload(user(false), account, csv("-2", "3"), null).andExpect(status().isForbidden());
        mvc.perform(post("/api/imports/accounts").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Injected\",\"currency\":\"TND\",\"currentBalance\":0,\"userId\":\"" + other.id() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidCurrencyAndPrecisionBatchesPersistNoPartialTransactions() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "Relevé", "TND");
        String csv = "date;description;amount;currency\n" + TODAY + ";Valid;-2;TND\n"
                + TODAY + ";Wrong currency;-3;EUR\n" + TODAY + ";Wrong scale;-1.12345;TND";
        UUID job = job(upload(owner, account, csv, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.invalidCount").value(2)).andExpect(jsonPath("$.canImport").value(false)).andReturn());
        confirm(owner, account, job, csv, null).andExpect(status().isBadRequest());
        assertThat(transactionCount(owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "rejected_import_row WHERE import_job_id=?", Integer.class, job)).isEqualTo(2);
    }

    @Test
    void rejectsChangedFileMappingAndExpiredPreviewWithoutWritingTransactions() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "Relevé", "TND");
        String csv = csv("-2", "3");
        UUID job = job(upload(owner, account, csv, null).andReturn());
        confirm(owner, account, job, csv("-4", "3"), null).andExpect(status().isConflict());
        String mapping = "{\"dateColumn\":\"date\",\"descriptionColumn\":\"amount\",\"amountColumn\":\"description\"}";
        confirm(owner, account, job, csv, mapping).andExpect(status().isConflict());
        jdbc.update("UPDATE " + S + "import_job SET created_at=CURRENT_TIMESTAMP - INTERVAL '31 minutes' WHERE id=?", job);
        confirm(owner, account, job, csv, null).andExpect(status().isConflict());
        assertThat(transactionCount(owner)).isZero();
    }

    @Test
    void correctsUnknownAndRepeatedColumnMappingsThroughPreview() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "Relevé", "TND");
        String csv = "jour;texte;valeur\n" + TODAY + ";Achat;-2";
        UUID job = job(upload(owner, account, csv, null).andExpect(jsonPath("$.canImport").value(false)).andReturn());
        upload(owner, account, csv, "{\"dateColumn\":\"jour\",\"descriptionColumn\":\"jour\",\"amountColumn\":\"valeur\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.canImport").value(false));
        String mapping = "{\"dateColumn\":\"jour\",\"descriptionColumn\":\"texte\",\"amountColumn\":\"valeur\"}";
        upload(owner, account, csv, mapping).andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(job.toString()))
                .andExpect(jsonPath("$.canImport").value(true));
        confirm(owner, account, job, csv, mapping).andExpect(status().isOk()).andExpect(jsonPath("$.importedCount").value(1));
    }

    @Test
    void concurrentConfirmationCannotImportTheSameFileTwice() throws Exception {
        User owner = user(true);
        UUID account = account(owner, "Relevé", "TND");
        String csv = csv("-2", "3");
        UUID job = job(upload(owner, account, csv, null).andReturn());
        var first = CompletableFuture.supplyAsync(() -> runConfirmation(owner, account, job, csv));
        var second = CompletableFuture.supplyAsync(() -> runConfirmation(owner, account, job, csv));
        assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(transactionCount(owner)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "audit_log_entry WHERE actor_id=? AND action='CSV_IMPORT_COMPLETED' "
                + "AND change_summary='{}'::jsonb", Integer.class, owner.id())).isEqualTo(1);
    }

    private int runConfirmation(User user, UUID account, UUID job, String csv) {
        try { return confirm(user, account, job, csv, null).andReturn().getResponse().getStatus(); }
        catch (Exception exception) { throw new IllegalStateException("Concurrent confirmation failed", exception); }
    }

    private ResultActions upload(User user, UUID account, String csv, String mapping) throws Exception {
        return multipartRequest("/api/imports/csv/preview", user, account, csv, mapping);
    }

    private ResultActions confirm(User user, UUID account, UUID job, String csv, String mapping) throws Exception {
        return multipartRequest("/api/imports/csv/" + job + "/confirm", user, account, csv, mapping);
    }

    private ResultActions multipartRequest(String path, User user, UUID account, String csv, String mapping) throws Exception {
        return mvc.perform(multipart(path)
                .file(new MockMultipartFile("file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("request", "", "application/json", ("{\"accountId\":\"" + account + "\",\"mapping\":"
                        + (mapping == null ? "null" : mapping) + "}").getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private UUID account(User user, String name, String currency) throws Exception {
        MvcResult result = mvc.perform(post("/api/imports/accounts").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"currency\":\"" + currency + "\",\"currentBalance\":4200.0000}"))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private User user(boolean active) throws Exception {
        var result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"csv-" + UUID.randomUUID() + "@example.test\",\"password\":\"TestOnlyPass123!\",\"firstName\":\"CSV\",\"lastName\":\"Tester\",\"displayName\":\"CSV Tester\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(body.get("user").get("id").asText());
        jdbc.update("UPDATE " + S + "interface_preference SET time_zone='Africa/Tunis' WHERE user_id=?", id);
        if (active) {
            jdbc.update("UPDATE " + S + "app_user SET status='ACTIVE' WHERE id=?", id);
            jdbc.update("UPDATE " + S + "authentication_identity SET status='ACTIVE',verified_at=CURRENT_TIMESTAMP WHERE user_id=?", id);
        }
        return new User(id, body.get("accessToken").asText());
    }

    private UUID job(MvcResult result) throws Exception { return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("jobId").asText()); }
    private int transactionCount(User user) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "transaction_record WHERE user_id=?", Integer.class, user.id()); }
    private String csv(String first, String second) { return "date;description;amount\n" + TODAY + ";Achat;" + first + "\n" + TODAY + ";Achat;" + second + "\n"; }
    private String bearer(User user) { return "Bearer " + user.token(); }
    private record User(UUID id, String token) { }
}
