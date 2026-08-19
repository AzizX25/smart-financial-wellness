package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor;
import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.CashFlowDirection;
import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.ForecastInput;
import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.UnavailableReason;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ForecastInputExtractionIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final UUID UNCATEGORIZED =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRANSFER_OUT =
            UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");
    private static final LocalDate CALCULATION_DATE = LocalDate.of(2026, 8, 15);

    @Autowired ForecastInputExtractor extractor;
    @Autowired AppUserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void selectsLatestCommonLocalDateAndAggregatesOnlyOwnedSameCurrencyActiveAccounts() {
        AppUser user = createUser();
        AppUser otherUser = createUser();
        UUID first = insertManualAccount(user.getId(), "TND", "ACTIVE");
        UUID second = insertManualAccount(user.getId(), "TND", "ACTIVE");
        UUID archived = insertManualAccount(user.getId(), "TND", "ARCHIVED");
        UUID foreignCurrency = insertManualAccount(user.getId(), "EUR", "ACTIVE");
        UUID foreignUser = insertManualAccount(otherUser.getId(), "TND", "ACTIVE");

        insertSnapshot(first, "100.0000", "2026-08-10T08:00:00Z");
        insertSnapshot(first, "120.1234", "2026-08-10T22:30:00Z");
        insertSnapshot(first, "500.0000", "2026-08-11T08:00:00Z");
        insertSnapshot(second, "-20.1234", "2026-08-09T23:30:00Z");
        insertSnapshot(second, "600.0000", "2026-08-12T08:00:00Z");
        insertSnapshot(first, "999.0000", "2026-08-16T08:00:00Z");
        insertSnapshot(second, "999.0000", "2026-08-16T08:00:00Z");
        insertSnapshot(archived, "999.0000", "2026-08-10T08:00:00Z");
        insertSnapshot(foreignCurrency, "999.0000", "2026-08-10T08:00:00Z");
        insertSnapshot(foreignUser, "999.0000", "2026-08-10T08:00:00Z");

        ForecastInput result = extractor.extract(user.getId(), "TND", TUNIS, CALCULATION_DATE);

        assertThat(result.available()).isTrue();
        assertThat(result.unavailableReason()).isEqualTo(UnavailableReason.NONE);
        assertThat(result.cutoffDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(result.startingBalance()).isEqualByComparingTo("100.0000");
        assertThat(result.startingBalance().scale()).isEqualTo(4);
        assertThat(result.selectedAccountCount()).isEqualTo(2);
        assertThat(result.timeZone()).isEqualTo(TUNIS);
    }

    @Test
    void returnsSpecificUnavailableReasonsWithoutDroppingAccountsOrUsingFutureSnapshots() {
        AppUser noAccountUser = createUser();
        assertUnavailable(noAccountUser.getId(), UnavailableReason.NO_ELIGIBLE_ACCOUNTS);

        AppUser missingUser = createUser();
        UUID withSnapshot = insertManualAccount(missingUser.getId(), "TND", "ACTIVE");
        insertManualAccount(missingUser.getId(), "TND", "ACTIVE");
        insertSnapshot(withSnapshot, "1.0000", "2026-08-10T08:00:00Z");
        assertUnavailable(missingUser.getId(), UnavailableReason.ACCOUNT_MISSING_SNAPSHOT);

        AppUser futureOnlyUser = createUser();
        UUID futureOnlyAccount = insertManualAccount(futureOnlyUser.getId(), "TND", "ACTIVE");
        insertSnapshot(futureOnlyAccount, "1.0000", "2026-08-16T08:00:00Z");
        assertUnavailable(futureOnlyUser.getId(), UnavailableReason.ACCOUNT_MISSING_SNAPSHOT);

        AppUser noCommonUser = createUser();
        UUID noCommonFirst = insertManualAccount(noCommonUser.getId(), "TND", "ACTIVE");
        UUID noCommonSecond = insertManualAccount(noCommonUser.getId(), "TND", "ACTIVE");
        insertSnapshot(noCommonFirst, "1.0000", "2026-08-10T08:00:00Z");
        insertSnapshot(noCommonSecond, "1.0000", "2026-08-11T08:00:00Z");
        assertUnavailable(noCommonUser.getId(), UnavailableReason.NO_COMMON_CUTOFF);

        AppUser staleUser = createUser();
        UUID staleFirst = insertManualAccount(staleUser.getId(), "TND", "ACTIVE");
        UUID staleSecond = insertManualAccount(staleUser.getId(), "TND", "ACTIVE");
        insertSnapshot(staleFirst, "1.0000", "2026-07-31T08:00:00Z");
        insertSnapshot(staleSecond, "1.0000", "2026-07-31T09:00:00Z");
        ForecastInput stale = extractor.extract(staleUser.getId(), "TND", TUNIS, CALCULATION_DATE);
        assertThat(stale.available()).isFalse();
        assertThat(stale.unavailableReason()).isEqualTo(UnavailableReason.CUTOFF_OUTSIDE_CURRENT_MONTH);
        assertThat(stale.cutoffDate()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void extractsOnlyEligibleCashFlowsWithoutUsingCategoriesAsEligibility() {
        AppUser user = createUser();
        AppUser other = createUser();
        BankFixture bank = createBankFixture(user.getId(), "TND");
        UUID first = bank.firstAccountId();
        UUID second = bank.secondAccountId();
        insertSnapshot(first, "500.0000", "2026-08-15T08:00:00Z");
        insertSnapshot(second, "250.0000", "2026-08-15T09:00:00Z");
        UUID inactiveCategory = insertInactiveCategory(user.getId());

        insertManualTransaction(user.getId(), first, UNCATEGORIZED, "2026-07-20", "10.0000", "CREDIT", "TND");
        insertFileTransaction(user.getId(), first, TRANSFER_OUT, "2026-07-21", "4.0000", "DEBIT", "TND");
        insertBankTransaction(bank, first, UNCATEGORIZED, "2026-07-22", "7.0000", "CREDIT", "POSTED");
        insertBankTransaction(bank, first, UNCATEGORIZED, "2026-07-23", "101.0000", "CREDIT", "PENDING");
        insertBankTransaction(bank, first, UNCATEGORIZED, "2026-07-24", "102.0000", "CREDIT", "REVERSED");
        insertBankTransaction(bank, first, UNCATEGORIZED, "2026-07-25", "103.0000", "CREDIT", "UNKNOWN");
        insertManualTransaction(user.getId(), first, TRANSFER_OUT, "2026-07-26", "6.0000", "DEBIT", "TND");
        insertManualTransaction(user.getId(), first, UNCATEGORIZED, "2026-07-27", "3.0000", "CREDIT", "TND");
        insertManualTransaction(user.getId(), first, inactiveCategory, "2026-07-28", "2.0000", "DEBIT", "TND");
        insertManualTransaction(user.getId(), first, UNCATEGORIZED, "2026-07-29", "1.0000", "CREDIT", "TND");

        UUID linkedOutgoing = insertManualTransaction(
                user.getId(), first, TRANSFER_OUT, "2026-07-30", "5.0000", "DEBIT", "TND");
        UUID linkedIncoming = insertManualTransaction(
                user.getId(), second, UNCATEGORIZED, "2026-07-30", "5.0000", "CREDIT", "TND");
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "internal_transfer "
                        + "(outgoing_transaction_id, incoming_transaction_id, detection_method) "
                        + "VALUES (?, ?, 'USER')",
                linkedOutgoing,
                linkedIncoming);

        UUID eurAccount = insertManualAccount(user.getId(), "EUR", "ACTIVE");
        insertManualTransaction(user.getId(), eurAccount, UNCATEGORIZED, "2026-07-20", "200.0000", "CREDIT", "EUR");
        UUID otherAccount = insertManualAccount(other.getId(), "TND", "ACTIVE");
        insertManualTransaction(other.getId(), otherAccount, UNCATEGORIZED, "2026-07-20", "300.0000", "CREDIT", "TND");
        insertManualTransaction(user.getId(), first, UNCATEGORIZED, "2026-08-01", "400.0000", "CREDIT", "TND");
        insertManualTransaction(user.getId(), first, UNCATEGORIZED, "2026-09-01", "500.0000", "CREDIT", "TND");

        ForecastInput result = extractor.extract(user.getId(), "TND", TUNIS, CALCULATION_DATE);

        assertThat(result.available()).isTrue();
        assertThat(result.startingBalance()).isEqualByComparingTo("750.0000");
        assertThat(result.historicalCashFlows()).extracting(cashFlow ->
                        cashFlow.direction() + ":" + cashFlow.amount().toPlainString())
                .containsExactly(
                        "CREDIT:10.0000",
                        "DEBIT:4.0000",
                        "CREDIT:7.0000",
                        "DEBIT:6.0000",
                        "CREDIT:3.0000",
                        "DEBIT:2.0000",
                        "CREDIT:1.0000");
        assertThat(result.historicalCashFlows().stream()
                .filter(flow -> flow.direction() == CashFlowDirection.CREDIT)
                .map(flow -> flow.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("21.0000");
        assertThat(result.historicalCashFlows().stream()
                .filter(flow -> flow.direction() == CashFlowDirection.DEBIT)
                .map(flow -> flow.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("12.0000");
    }

    private void assertUnavailable(UUID userId, UnavailableReason expected) {
        ForecastInput result = extractor.extract(userId, "TND", TUNIS, CALCULATION_DATE);
        assertThat(result.available()).isFalse();
        assertThat(result.unavailableReason()).isEqualTo(expected);
    }

    private AppUser createUser() {
        UUID id = UUID.randomUUID();
        return userRepository.saveAndFlush(new AppUser(id, "Forecast", "Tester", "Forecast " + id));
    }

    private UUID insertManualAccount(UUID userId, String currency, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_account "
                        + "(id, user_id, account_origin, name, account_type, currency, status) "
                        + "VALUES (?, ?, 'MANUAL', 'Forecast account', 'CHECKING', ?, ?)",
                id,
                userId,
                currency,
                status);
        return id;
    }

    private void insertSnapshot(UUID accountId, String balance, String observedAt) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "balance_snapshot "
                        + "(financial_account_id, ledger_balance, available_balance, currency, source_type, observed_at) "
                        + "SELECT id, ?, ?, currency, 'MANUAL', ? FROM " + SCHEMA
                        + "financial_account WHERE id = ?",
                new BigDecimal(balance),
                new BigDecimal(balance),
                Timestamp.from(Instant.parse(observedAt)),
                accountId);
    }

    private UUID insertInactiveCategory(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "category "
                        + "(id, user_id, code, label, taxonomy_version, direction, nature, active) "
                        + "VALUES (?, ?, ?, 'Inactive forecast', 'user-v1', 'DEBIT', 'EXPENSE', FALSE)",
                id,
                userId,
                "INACTIVE_FORECAST_" + id.toString().replace("-", ""));
        return id;
    }

    private UUID insertManualTransaction(
            UUID userId,
            UUID accountId,
            UUID categoryId,
            String bookingDate,
            String amount,
            String direction,
            String currency) {
        UUID id = UUID.randomUUID();
        boolean uncategorized = UNCATEGORIZED.equals(categoryId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, booking_date, amount, "
                        + "direction, currency, raw_description, normalized_description, category_source, "
                        + "categorized_at) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', ?::date, ?, ?, ?, "
                        + "'Forecast cash flow', 'forecast cash flow', ?, ?)",
                id,
                userId,
                accountId,
                categoryId,
                bookingDate,
                new BigDecimal(amount),
                direction,
                currency,
                uncategorized ? "UNCATEGORIZED" : "USER",
                uncategorized ? null : Timestamp.from(Instant.now()));
        return id;
    }

    private void insertFileTransaction(
            UUID userId,
            UUID accountId,
            UUID categoryId,
            String bookingDate,
            String amount,
            String direction,
            String currency) {
        UUID sourceFileId = UUID.randomUUID();
        UUID importJobId = UUID.randomUUID();
        boolean uncategorized = UNCATEGORIZED.equals(categoryId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "source_file "
                        + "(id, user_id, file_type, original_filename, mime_type, size_bytes, sha256, storage_reference) "
                        + "VALUES (?, ?, 'CSV', 'forecast.csv', 'text/csv', 1, ?, ?)",
                sourceFileId,
                userId,
                sourceFileId.toString().replace("-", "") + sourceFileId.toString().replace("-", ""),
                "test://forecast/" + sourceFileId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "import_job "
                        + "(id, user_id, financial_account_id, source_file_id) VALUES (?, ?, ?, ?)",
                importJobId,
                userId,
                accountId,
                sourceFileId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, import_job_id, "
                        + "source_file_id, source_row_number, booking_date, amount, direction, currency, "
                        + "raw_description, normalized_description, category_source, categorized_at) "
                        + "VALUES (?, ?, ?, ?, 'FILE_IMPORT', ?, ?, 1, ?::date, ?, ?, ?, "
                        + "'Forecast file flow', 'forecast file flow', ?, ?)",
                UUID.randomUUID(),
                userId,
                accountId,
                categoryId,
                importJobId,
                sourceFileId,
                bookingDate,
                new BigDecimal(amount),
                direction,
                currency,
                uncategorized ? "UNCATEGORIZED" : "USER",
                uncategorized ? null : Timestamp.from(Instant.now()));
    }

    private BankFixture createBankFixture(UUID userId, String currency) {
        UUID connectionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID syncJobId = UUID.randomUUID();
        UUID firstAccountId = UUID.randomUUID();
        UUID secondAccountId = UUID.randomUUID();
        String providerCode = "FORECAST_" + connectionId.toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_connection "
                        + "(id, user_id, provider_code, external_customer_id, status) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE')",
                connectionId,
                userId,
                providerCode,
                "CUSTOMER-" + connectionId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_connection_authorization "
                        + "(id, bank_connection_id, status, secret_reference, granted_scopes, consented_at, expires_at) "
                        + "VALUES (?, ?, 'ACTIVE', ?, "
                        + "ARRAY['ACCOUNTS_READ','BALANCES_READ','TRANSACTIONS_READ']::VARCHAR(40)[], "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day')",
                authorizationId,
                connectionId,
                "test://forecast/" + authorizationId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "sync_job "
                        + "(id, bank_connection_id, authorization_id, status, started_at) "
                        + "VALUES (?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP)",
                syncJobId,
                connectionId,
                authorizationId);
        insertBankAccount(firstAccountId, userId, connectionId, providerCode, currency);
        insertBankAccount(secondAccountId, userId, connectionId, providerCode, currency);
        return new BankFixture(userId, syncJobId, firstAccountId, secondAccountId, currency);
    }

    private void insertBankAccount(
            UUID accountId,
            UUID userId,
            UUID connectionId,
            String providerCode,
            String currency) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_account "
                        + "(id, user_id, bank_connection_id, account_origin, external_account_id, name, "
                        + "account_type, currency, provider_code, status) "
                        + "VALUES (?, ?, ?, 'BANK_CONNECTION', ?, 'Forecast bank account', "
                        + "'CHECKING', ?, ?, 'ACTIVE')",
                accountId,
                userId,
                connectionId,
                "ACCOUNT-" + accountId,
                currency,
                providerCode);
    }

    private void insertBankTransaction(
            BankFixture fixture,
            UUID accountId,
            UUID categoryId,
            String bookingDate,
            String amount,
            String direction,
            String providerStatus) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, sync_job_id, "
                        + "external_transaction_id, booking_date, amount, direction, currency, raw_description, "
                        + "normalized_description, category_source, provider_status, first_imported_at, "
                        + "last_synchronized_at) VALUES (?, ?, ?, ?, 'BANK_SYNC', ?, ?, ?::date, ?, ?, ?, "
                        + "'Forecast bank flow', 'forecast bank flow', 'UNCATEGORIZED', ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id,
                fixture.userId(),
                accountId,
                categoryId,
                fixture.syncJobId(),
                "TX-" + id,
                bookingDate,
                new BigDecimal(amount),
                direction,
                fixture.currency(),
                providerStatus);
    }

    private record BankFixture(
            UUID userId,
            UUID syncJobId,
            UUID firstAccountId,
            UUID secondAccountId,
            String currency) {
    }
}
