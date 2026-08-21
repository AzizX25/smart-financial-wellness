package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class DatabaseMigrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatedCompleteBackendSchema() {
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "flyway_schema_history "
                        + "WHERE version IS NOT NULL AND success",
                Integer.class);
        Integer applicationTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'smart_financial_wellness' "
                        + "AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history'",
                Integer.class);
        Integer constraints = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint c "
                        + "JOIN pg_namespace n ON n.oid = c.connamespace "
                        + "LEFT JOIN pg_class r ON r.oid = c.conrelid "
                        + "WHERE n.nspname = 'smart_financial_wellness' "
                        + "AND c.contype IN ('c', 'f', 'p', 'u', 'x') "
                        + "AND COALESCE(r.relname, '') <> 'flyway_schema_history'",
                Integer.class);
        Integer indexes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'smart_financial_wellness' "
                        + "AND tablename <> 'flyway_schema_history'",
                Integer.class);
        Integer functions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_proc p "
                        + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                        + "WHERE n.nspname = 'smart_financial_wellness'",
                Integer.class);
        Integer triggers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger t "
                        + "JOIN pg_class r ON r.oid = t.tgrelid "
                        + "JOIN pg_namespace n ON n.oid = r.relnamespace "
                        + "WHERE n.nspname = 'smart_financial_wellness' "
                        + "AND NOT t.tgisinternal",
                Integer.class);

        assertThat(successfulMigrations).isEqualTo(19);
        assertThat(applicationTables).isEqualTo(48);
        assertThat(constraints).isEqualTo(391);
        assertThat(indexes).isEqualTo(145);
        assertThat(functions).isEqualTo(44);
        assertThat(triggers).isEqualTo(71);
    }

    @Test
    void manualTransactionProvenanceIsExclusive() {
        UUID userId = createUser();
        UUID accountId = createManualAccount(userId, "TND");
        UUID categoryId = uncategorizedCategoryId();

        insertManualTransaction(userId, accountId, categoryId, UUID.randomUUID().toString());

        BankContext bank = createActiveBankContext(userId, "TND");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, sync_job_id, "
                        + "client_idempotency_key, fingerprint, booking_date, amount, direction, currency, "
                        + "raw_description, normalized_description, category_source) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', ?, ?, ?, CURRENT_DATE, 10, 'DEBIT', 'TND', "
                        + "'Invalid manual provenance', 'invalid manual provenance', 'UNCATEGORIZED')",
                UUID.randomUUID(), userId, accountId, categoryId, bank.syncJobId(),
                UUID.randomUUID().toString(), randomSha256()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void fileImportUsesFileAndRowForIdempotenceAcrossImportAttempts() {
        UUID userId = createUser();
        UUID accountId = createManualAccount(userId, "TND");
        UUID categoryId = uncategorizedCategoryId();
        UUID sourceFileId = createSourceFile(userId);
        UUID firstImportId = createImportJob(userId, accountId, sourceFileId);
        UUID secondImportId = createImportJob(userId, accountId, sourceFileId);

        insertFileTransaction(userId, accountId, categoryId, firstImportId, sourceFileId, 2);

        assertThatThrownBy(() -> insertFileTransaction(
                userId, accountId, categoryId, secondImportId, sourceFileId, 2))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void fileTransactionMustMatchImportAccountAndSourceFile() {
        UUID userId = createUser();
        UUID firstAccountId = createManualAccount(userId, "TND");
        UUID secondAccountId = createManualAccount(userId, "TND");
        UUID categoryId = uncategorizedCategoryId();
        UUID sourceFileId = createSourceFile(userId);
        UUID importId = createImportJob(userId, firstAccountId, sourceFileId);

        assertThatThrownBy(() -> insertFileTransaction(
                userId, secondAccountId, categoryId, importId, sourceFileId, 1))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void bankSyncUsesStableExternalIdentifierForIdempotence() {
        UUID userId = createUser();
        BankContext bank = createActiveBankContext(userId, "TND");
        UUID categoryId = uncategorizedCategoryId();
        String externalTransactionId = "mock-transaction-" + UUID.randomUUID();

        insertBankTransaction(userId, bank.accountId(), categoryId, bank.syncJobId(), externalTransactionId);

        assertThatThrownBy(() -> insertBankTransaction(
                userId, bank.accountId(), categoryId, bank.syncJobId(), externalTransactionId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void revokedAndExpiredBankAuthorizationsCannotLaunchSynchronization() {
        UUID userId = createUser();
        UUID revokedConnectionId = createConnection(userId, "REVOKED_TEST");
        UUID revokedAuthorizationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_connection_authorization "
                        + "(id, bank_connection_id, status, secret_reference, granted_scopes, "
                        + "consented_at, expires_at, revoked_at, revocation_reason) "
                        + "VALUES (?, ?, 'REVOKED', 'vault://revoked', "
                        + "ARRAY['ACCOUNTS_READ','BALANCES_READ','TRANSACTIONS_READ']::VARCHAR(40)[], "
                        + "CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP + INTERVAL '1 day', "
                        + "CURRENT_TIMESTAMP - INTERVAL '1 day', 'USER_REQUEST')",
                revokedAuthorizationId, revokedConnectionId);

        assertThatThrownBy(() -> insertQueuedSyncJob(revokedConnectionId, revokedAuthorizationId))
                .isInstanceOf(DataAccessException.class);

        UUID expiredConnectionId = createConnection(userId, "EXPIRED_TEST");
        UUID expiredAuthorizationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_connection_authorization "
                        + "(id, bank_connection_id, status, granted_scopes, consented_at, expires_at) "
                        + "VALUES (?, ?, 'EXPIRED', "
                        + "ARRAY['ACCOUNTS_READ','BALANCES_READ','TRANSACTIONS_READ']::VARCHAR(40)[], "
                        + "CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day')",
                expiredAuthorizationId, expiredConnectionId);

        assertThatThrownBy(() -> insertQueuedSyncJob(expiredConnectionId, expiredAuthorizationId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void bankConnectionAllowsOnlyOneActiveAuthorization() {
        UUID userId = createUser();
        UUID connectionId = createConnection(userId, "SINGLE_ACTIVE");
        insertActiveBankAuthorization(connectionId);

        assertThatThrownBy(() -> insertActiveBankAuthorization(connectionId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void refreshSessionRotationStaysInOneFamilyAndCannotBeConsumedTwice() {
        UUID userId = createUser();
        UUID identityId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "authentication_identity "
                        + "(id, user_id, normalized_identifier, status, verified_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)",
                identityId,
                userId,
                "user-" + userId + "@example.test");

        UUID familyId = UUID.randomUUID();
        UUID firstSessionId = insertRefreshSession(identityId, familyId);
        UUID successorSessionId = insertRefreshSession(identityId, familyId);
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "refresh_session "
                        + "SET last_used_at = CURRENT_TIMESTAMP, revoked_at = CURRENT_TIMESTAMP, "
                        + "revocation_reason = 'ROTATED', replaced_by_session_id = ? WHERE id = ?",
                successorSessionId,
                firstSessionId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "refresh_session "
                        + "SET last_used_at = CURRENT_TIMESTAMP + INTERVAL '1 second' WHERE id = ?",
                firstSessionId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsCrossUserImportAndPrivateCategoryAccess() {
        UUID firstUserId = createUser();
        UUID secondUserId = createUser();
        UUID firstUserAccountId = createManualAccount(firstUserId, "TND");
        UUID secondUserFileId = createSourceFile(secondUserId);

        assertThatThrownBy(() -> createImportJob(
                secondUserId, firstUserAccountId, secondUserFileId))
                .isInstanceOf(DataAccessException.class);

        UUID secondUserAccountId = createManualAccount(secondUserId, "TND");
        UUID firstUserCategoryId = createPrivateCategory(firstUserId, null, "PRIVATE");
        assertThatThrownBy(() -> insertManualTransaction(
                secondUserId, secondUserAccountId, firstUserCategoryId, UUID.randomUUID().toString()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void privateCategoryHierarchyCannotCrossUsers() {
        UUID firstUserId = createUser();
        UUID secondUserId = createUser();
        UUID firstUserParentId = createPrivateCategory(firstUserId, null, "PARENT");

        UUID validChildId = createPrivateCategory(firstUserId, firstUserParentId, "CHILD");
        assertThat(validChildId).isNotNull();

        assertThatThrownBy(() -> createPrivateCategory(
                secondUserId, firstUserParentId, "FOREIGN_CHILD"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void internalTransferRequiresDistinctAccountsEqualAmountAndOppositeDirections() {
        UUID userId = createUser();
        UUID firstAccountId = createManualAccount(userId, "TND");
        UUID secondAccountId = createManualAccount(userId, "TND");
        UUID categoryId = uncategorizedCategoryId();
        UUID outgoingId = insertManualTransactionWithDetails(
                userId, firstAccountId, categoryId, "DEBIT", new BigDecimal("50.00"));
        UUID incomingId = insertManualTransactionWithDetails(
                userId, secondAccountId, categoryId, "CREDIT", new BigDecimal("50.00"));

        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "internal_transfer "
                        + "(id, outgoing_transaction_id, incoming_transaction_id, detection_method) "
                        + "VALUES (?, ?, ?, 'RULES')",
                UUID.randomUUID(),
                outgoingId,
                incomingId);

        UUID otherOutgoingId = insertManualTransactionWithDetails(
                userId, firstAccountId, categoryId, "DEBIT", new BigDecimal("70.00"));
        UUID mismatchedIncomingId = insertManualTransactionWithDetails(
                userId, secondAccountId, categoryId, "CREDIT", new BigDecimal("60.00"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "internal_transfer "
                        + "(id, outgoing_transaction_id, incoming_transaction_id, detection_method) "
                        + "VALUES (?, ?, ?, 'RULES')",
                UUID.randomUUID(),
                otherOutgoingId,
                mismatchedIncomingId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void confirmedGoalAllocationsCannotExceedSavingCapacity() {
        UUID userId = createUser();
        UUID planId = createMonthlyPlan(userId, new BigDecimal("100.00"));
        UUID firstGoalId = createGoal(userId, "First goal");
        UUID secondGoalId = createGoal(userId, "Second goal");

        insertConfirmedGoalAllocation(planId, firstGoalId, new BigDecimal("80.00"));

        assertThatThrownBy(() -> insertConfirmedGoalAllocation(
                planId, secondGoalId, new BigDecimal("30.00")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void transactionContributionRequiresMatchingSourceUserAndCurrency() {
        UUID userId = createUser();
        UUID accountId = createManualAccount(userId, "TND");
        UUID categoryId = uncategorizedCategoryId();
        UUID goalId = createGoal(userId, "Contribution goal");
        UUID transactionId = insertManualTransaction(
                userId, accountId, categoryId, UUID.randomUUID().toString());

        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "goal_contribution "
                        + "(id, financial_goal_id, transaction_id, source, amount, contribution_date) "
                        + "VALUES (?, ?, ?, 'TRANSACTION', 25, CURRENT_DATE)",
                UUID.randomUUID(), goalId, transactionId);

        UUID anotherTransactionId = insertManualTransaction(
                userId, accountId, categoryId, UUID.randomUUID().toString());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "goal_contribution "
                        + "(id, financial_goal_id, transaction_id, source, amount, contribution_date) "
                        + "VALUES (?, ?, ?, 'MANUAL', 10, CURRENT_DATE)",
                UUID.randomUUID(), goalId, anotherTransactionId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void producedWellnessEvaluationIsImmutable() {
        EvaluationContext evaluation = createProducedEvaluation();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "wellness_evaluation SET global_score = 10 WHERE id = ?",
                evaluation.evaluationId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void failedForecastKeepsItsSelectedAccountScope() {
        UUID userId = createUser();
        UUID accountId = createManualAccount(userId, "TND");
        UUID forecastRunId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "forecast_run "
                        + "(id, user_id, forecast_start_date, forecast_end_date, forecast_horizon_days, "
                        + "currency, model_name, model_version, pipeline_version, data_cutoff_at) "
                        + "VALUES (?, ?, CURRENT_DATE, CURRENT_DATE + 2, 3, 'TND', "
                        + "'forecast-model', 'v1', 'pipeline-v1', CURRENT_TIMESTAMP)",
                forecastRunId,
                userId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "forecast_run_account "
                        + "(forecast_run_id, financial_account_id, reference_balance, reference_balance_at) "
                        + "VALUES (?, ?, 100, CURRENT_TIMESTAMP)",
                forecastRunId,
                accountId);
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "forecast_run "
                        + "SET status = 'FAILED', started_at = CURRENT_TIMESTAMP, "
                        + "completed_at = CURRENT_TIMESTAMP, safe_error_code = 'MODEL_UNAVAILABLE' "
                        + "WHERE id = ?",
                forecastRunId);

        Integer selectedAccounts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "forecast_run_account WHERE forecast_run_id = ?",
                Integer.class,
                forecastRunId);
        assertThat(selectedAccounts).isEqualTo(1);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "forecast_run_account "
                        + "SET reference_balance = 200 WHERE forecast_run_id = ? AND financial_account_id = ?",
                forecastRunId,
                accountId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void activeRecommendationRequiresJustificationFromSameEvaluation() {
        EvaluationContext evaluation = createProducedEvaluation();
        UUID recommendationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "personalized_recommendation "
                        + "(id, user_id, wellness_evaluation_id, recommendation_type, title, message, "
                        + "informational_disclaimer, priority, status, generation_method, rules_version) "
                        + "VALUES (?, ?, ?, 'SAVE_MORE', 'Save more', 'Consider saving more.', "
                        + "'Informational guidance only.', 'MEDIUM', 'DRAFT', 'RULES', 'rules-v1')",
                recommendationId, evaluation.userId(), evaluation.evaluationId());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "personalized_recommendation SET status = 'ACTIVE' WHERE id = ?",
                recommendationId))
                .isInstanceOf(DataAccessException.class);

        EvaluationContext otherEvaluation = createProducedEvaluation();
        assertThatThrownBy(() -> insertRecommendationJustification(
                recommendationId, otherEvaluation.indicatorId()))
                .isInstanceOf(DataAccessException.class);

        insertRecommendationJustification(recommendationId, evaluation.indicatorId());
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "personalized_recommendation SET status = 'ACTIVE' WHERE id = ?",
                recommendationId);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM " + SCHEMA + "personalized_recommendation WHERE id = ?",
                String.class,
                recommendationId);
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    void auditLogIsAppendOnly() {
        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "audit_log_entry "
                        + "(id, actor_type, action, resource_type, outcome, correlation_id) "
                        + "VALUES (?, 'SYSTEM', 'TEST_ACTION', 'TEST_RESOURCE', 'SUCCEEDED', ?)",
                auditId, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "audit_log_entry SET outcome = 'FAILED' WHERE id = ?",
                auditId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM " + SCHEMA + "audit_log_entry WHERE id = ?",
                auditId))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID createUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "app_user (id, display_name, status) VALUES (?, ?, 'ACTIVE')",
                userId,
                "Test user " + userId);
        return userId;
    }

    private UUID createManualAccount(UUID userId, String currency) {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_account "
                        + "(id, user_id, account_origin, name, account_type, currency) "
                        + "VALUES (?, ?, 'MANUAL', ?, 'CHECKING', ?)",
                accountId,
                userId,
                "Manual account " + accountId,
                currency);
        return accountId;
    }

    private UUID createSourceFile(UUID userId) {
        UUID sourceFileId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "source_file "
                        + "(id, user_id, file_type, original_filename, mime_type, size_bytes, sha256, "
                        + "storage_reference) VALUES (?, ?, 'CSV', 'transactions.csv', 'text/csv', 128, ?, ?)",
                sourceFileId,
                userId,
                randomSha256(),
                "storage://" + sourceFileId);
        return sourceFileId;
    }

    private UUID createImportJob(UUID userId, UUID accountId, UUID sourceFileId) {
        UUID importJobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "import_job "
                        + "(id, user_id, financial_account_id, source_file_id) VALUES (?, ?, ?, ?)",
                importJobId,
                userId,
                accountId,
                sourceFileId);
        return importJobId;
    }

    private UUID insertManualTransaction(
            UUID userId,
            UUID accountId,
            UUID categoryId,
            String idempotencyKey) {
        return insertManualTransactionWithDetails(
                userId, accountId, categoryId, "DEBIT", new BigDecimal("25.00"), idempotencyKey);
    }

    private UUID insertManualTransactionWithDetails(
            UUID userId,
            UUID accountId,
            UUID categoryId,
            String direction,
            BigDecimal amount) {
        return insertManualTransactionWithDetails(
                userId, accountId, categoryId, direction, amount, UUID.randomUUID().toString());
    }

    private UUID insertManualTransactionWithDetails(
            UUID userId,
            UUID accountId,
            UUID categoryId,
            String direction,
            BigDecimal amount,
            String idempotencyKey) {
        UUID transactionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, "
                        + "client_idempotency_key, fingerprint, booking_date, amount, direction, currency, "
                        + "raw_description, normalized_description, category_source) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', ?, ?, CURRENT_DATE, ?, ?, 'TND', "
                        + "'Manual transaction', 'manual transaction', 'UNCATEGORIZED')",
                transactionId,
                userId,
                accountId,
                categoryId,
                idempotencyKey,
                randomSha256(),
                amount,
                direction);
        return transactionId;
    }

    private void insertFileTransaction(
            UUID userId,
            UUID accountId,
            UUID categoryId,
            UUID importJobId,
            UUID sourceFileId,
            int sourceRowNumber) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, import_job_id, "
                        + "source_file_id, source_row_number, fingerprint, booking_date, amount, direction, "
                        + "currency, raw_description, normalized_description, category_source) "
                        + "VALUES (?, ?, ?, ?, 'FILE_IMPORT', ?, ?, ?, ?, CURRENT_DATE, 30, 'DEBIT', "
                        + "'TND', 'Imported transaction', 'imported transaction', 'UNCATEGORIZED')",
                UUID.randomUUID(),
                userId,
                accountId,
                categoryId,
                importJobId,
                sourceFileId,
                sourceRowNumber,
                randomSha256());
    }

    private BankContext createActiveBankContext(UUID userId, String currency) {
        UUID connectionId = createConnection(userId, "MOCK_BANK");
        UUID authorizationId = insertActiveBankAuthorization(connectionId);

        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_account "
                        + "(id, user_id, bank_connection_id, account_origin, external_account_id, name, "
                        + "masked_identifier, account_type, currency, provider_code) "
                        + "VALUES (?, ?, ?, 'BANK_CONNECTION', ?, 'Mock bank account', '****1234', "
                        + "'CHECKING', ?, 'MOCK_BANK')",
                accountId,
                userId,
                connectionId,
                "external-account-" + accountId,
                currency);

        UUID syncJobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "sync_job "
                        + "(id, bank_connection_id, authorization_id, status, started_at) "
                        + "VALUES (?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP)",
                syncJobId,
                connectionId,
                authorizationId);
        return new BankContext(connectionId, authorizationId, accountId, syncJobId);
    }

    private UUID insertActiveBankAuthorization(UUID connectionId) {
        UUID authorizationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_connection_authorization "
                        + "(id, bank_connection_id, status, secret_reference, granted_scopes, "
                        + "consented_at, expires_at) VALUES (?, ?, 'ACTIVE', ?, "
                        + "ARRAY['ACCOUNTS_READ','BALANCES_READ','TRANSACTIONS_READ']::VARCHAR(40)[], "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day')",
                authorizationId,
                connectionId,
                "vault://authorization/" + authorizationId);
        return authorizationId;
    }

    private UUID insertRefreshSession(UUID identityId, UUID familyId) {
        UUID refreshSessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "refresh_session "
                        + "(id, authentication_identity_id, family_id, token_hash, expires_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                refreshSessionId,
                identityId,
                familyId,
                randomSha256());
        return refreshSessionId;
    }

    private UUID createConnection(UUID userId, String providerCode) {
        UUID connectionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_connection "
                        + "(id, user_id, provider_code, status) VALUES (?, ?, ?, 'ACTIVE')",
                connectionId,
                userId,
                providerCode + "-" + connectionId);
        return connectionId;
    }

    private void insertQueuedSyncJob(UUID connectionId, UUID authorizationId) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "sync_job "
                        + "(id, bank_connection_id, authorization_id, status) VALUES (?, ?, ?, 'QUEUED')",
                UUID.randomUUID(),
                connectionId,
                authorizationId);
    }

    private void insertBankTransaction(
            UUID userId,
            UUID accountId,
            UUID categoryId,
            UUID syncJobId,
            String externalTransactionId) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, sync_job_id, "
                        + "external_transaction_id, fingerprint, booking_date, amount, direction, currency, "
                        + "raw_description, normalized_description, category_source, provider_status, "
                        + "first_imported_at, last_synchronized_at) "
                        + "VALUES (?, ?, ?, ?, 'BANK_SYNC', ?, ?, ?, CURRENT_DATE, 40, 'DEBIT', 'TND', "
                        + "'Bank transaction', 'bank transaction', 'UNCATEGORIZED', 'POSTED', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                userId,
                accountId,
                categoryId,
                syncJobId,
                externalTransactionId,
                randomSha256());
    }

    private UUID createPrivateCategory(UUID userId, UUID parentId, String codePrefix) {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "category "
                        + "(id, user_id, parent_id, code, label, taxonomy_version, direction, nature) "
                        + "VALUES (?, ?, ?, ?, ?, 'user-v1', 'DEBIT', 'EXPENSE')",
                categoryId,
                userId,
                parentId,
                codePrefix + "_" + categoryId,
                codePrefix + " category");
        return categoryId;
    }

    private UUID createMonthlyPlan(UUID userId, BigDecimal savingCapacity) {
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "monthly_plan "
                        + "(id, user_id, period_month, currency, calculated_saving_capacity, "
                        + "calculation_method, method_version) VALUES (?, ?, ?, 'TND', ?, 'RULES', 'v1')",
                planId,
                userId,
                Date.valueOf(LocalDate.now().withDayOfMonth(1)),
                savingCapacity);
        return planId;
    }

    private UUID createGoal(UUID userId, String name) {
        UUID goalId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_goal "
                        + "(id, user_id, name, goal_type, currency, target_amount) "
                        + "VALUES (?, ?, ?, 'OTHER', 'TND', 1000)",
                goalId,
                userId,
                name + " " + goalId);
        return goalId;
    }

    private void insertConfirmedGoalAllocation(UUID planId, UUID goalId, BigDecimal amount) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "goal_allocation "
                        + "(id, monthly_plan_id, financial_goal_id, planned_amount, confirmed_amount, "
                        + "status, confirmed_at) VALUES (?, ?, ?, ?, ?, 'CONFIRMED', CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                planId,
                goalId,
                amount,
                amount);
    }

    private EvaluationContext createProducedEvaluation() {
        UUID userId = createUser();
        UUID accountId = createManualAccount(userId, "TND");
        UUID evaluationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "wellness_evaluation "
                        + "(id, user_id, period_start, period_end, currency, method_version) "
                        + "VALUES (?, ?, CURRENT_DATE - 30, CURRENT_DATE, 'TND', 'wellness-v1')",
                evaluationId,
                userId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "wellness_evaluation_account "
                        + "(wellness_evaluation_id, financial_account_id) VALUES (?, ?)",
                evaluationId,
                accountId);

        UUID dimensionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "evaluation_dimension "
                        + "(id, wellness_evaluation_id, dimension_type, score, weight, coverage_rate) "
                        + "VALUES (?, ?, 'MONTHLY_BALANCE', 75, 1, 1)",
                dimensionId,
                evaluationId);

        UUID indicatorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "evaluation_indicator "
                        + "(id, evaluation_dimension_id, indicator_code, numeric_value, unit, level, "
                        + "explanation, data_source, calculable) "
                        + "VALUES (?, ?, 'MONTHLY_NET', 100, 'TND', 'POSITIVE', "
                        + "'Positive monthly net balance', 'TRANSACTIONS', TRUE)",
                indicatorId,
                dimensionId);

        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "wellness_evaluation "
                        + "SET status = 'PRODUCED', global_score = 75, level = 'GOOD', "
                        + "coverage_rate = 1, produced_at = CURRENT_TIMESTAMP WHERE id = ?",
                evaluationId);
        return new EvaluationContext(userId, evaluationId, indicatorId);
    }

    private void insertRecommendationJustification(UUID recommendationId, UUID indicatorId) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "recommendation_justification "
                        + "(id, recommendation_id, evaluation_indicator_id, justification_type, "
                        + "factual_message) VALUES (?, ?, ?, 'INDICATOR', 'Based on a measured indicator')",
                UUID.randomUUID(),
                recommendationId,
                indicatorId);
    }

    private UUID uncategorizedCategoryId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA + "category "
                        + "WHERE user_id IS NULL AND code = 'UNCATEGORIZED'",
                UUID.class);
    }

    private String randomSha256() {
        String value = UUID.randomUUID().toString().replace("-", "");
        return value + value;
    }

    private record BankContext(
            UUID connectionId,
            UUID authorizationId,
            UUID accountId,
            UUID syncJobId) {
    }

    private record EvaluationContext(UUID userId, UUID evaluationId, UUID indicatorId) {
    }
}
