package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.salary.HistoricalSalaryRecommendationService;
import com.azizx25.smartfinancialwellness.salary.HistoricalSalaryRecommendationService.CategoryRecommendation;
import com.azizx25.smartfinancialwellness.salary.HistoricalSalaryRecommendationService.FallbackReason;
import com.azizx25.smartfinancialwellness.salary.HistoricalSalaryRecommendationService.Recommendation;
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
class HistoricalSalaryRecommendationIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final YearMonth PLAN_MONTH = YearMonth.of(2026, 8);

    @Autowired AppUserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired HistoricalSalaryRecommendationService recommendationService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void averagesThreeUsableMonthsWithCurrencyOwnershipZeroMonthsAndStatusExclusions() {
        AppUser user = createUser();
        AppUser other = createUser();
        Category food = systemCategory("FOOD");
        Category housing = systemCategory("HOUSING");
        Category salary = systemCategory("INCOME_SALARY");
        Category transfer = systemCategory("TRANSFER_OUT");
        Category unusedPrivateExpense = categoryRepository.saveAndFlush(
                new Category(user, "Animaux", Category.Nature.EXPENSE, null, null, Instant.now()));
        UUID inactiveExpense = insertInactiveExpenseCategory(user.getId());
        UUID tndAccount = insertManualAccount(user.getId(), "TND");
        UUID usdAccount = insertManualAccount(user.getId(), "USD");
        UUID otherAccount = insertManualAccount(other.getId(), "TND");

        insertManualTransaction(user.getId(), tndAccount, food.getId(), LocalDate.of(2026, 5, 5), "1", "DEBIT", "TND");
        insertManualTransaction(user.getId(), tndAccount, housing.getId(), LocalDate.of(2026, 6, 5), "30", "DEBIT", "TND");
        insertManualTransaction(user.getId(), tndAccount, housing.getId(), LocalDate.of(2026, 7, 5), "60", "DEBIT", "TND");
        insertManualTransaction(user.getId(), usdAccount, food.getId(), LocalDate.of(2026, 5, 5), "999", "DEBIT", "USD");
        insertManualTransaction(other.getId(), otherAccount, food.getId(), LocalDate.of(2026, 5, 5), "999", "DEBIT", "TND");
        insertManualTransaction(user.getId(), tndAccount, salary.getId(), LocalDate.of(2026, 6, 8), "500", "CREDIT", "TND");
        insertManualTransaction(user.getId(), tndAccount, transfer.getId(), LocalDate.of(2026, 7, 8), "500", "DEBIT", "TND");
        insertManualTransaction(user.getId(), tndAccount, inactiveExpense, LocalDate.of(2026, 7, 9), "500", "DEBIT", "TND");

        BankSyncFixture bank = createBankSyncFixture(user.getId(), "TND");
        insertBankTransaction(bank, food.getId(), "POSTED", "3");
        insertBankTransaction(bank, food.getId(), "PENDING", "1000");
        insertBankTransaction(bank, food.getId(), "REVERSED", "1000");

        Recommendation result = recommendationService.recommend(user.getId(), PLAN_MONTH, "TND");

        assertThat(result.periodStart()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(result.periodEndInclusive()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(result.usableMonthCount()).isEqualTo(3);
        assertThat(result.method()).isEqualTo(HistoricalSalaryRecommendationService.METHOD);
        assertThat(result.methodVersion()).isEqualTo(HistoricalSalaryRecommendationService.METHOD_VERSION);
        assertThat(result.fallbackReason()).isEqualTo(FallbackReason.NONE);
        assertThat(amountFor(result, food.getId())).isEqualByComparingTo("1.3333");
        assertThat(amountFor(result, housing.getId())).isEqualByComparingTo("30.0000");
        assertThat(amountFor(result, unusedPrivateExpense.getId())).isEqualByComparingTo("0.0000");
        assertThat(result.categories()).noneMatch(item -> item.categoryId().equals(inactiveExpense));
    }

    @Test
    void usesOneOrTwoUsableMonthsAndCountsMissingCategoryAsZero() {
        Category food = systemCategory("FOOD");
        Category housing = systemCategory("HOUSING");

        AppUser oneMonthUser = createUser();
        UUID oneMonthAccount = insertManualAccount(oneMonthUser.getId(), "TND");
        insertManualTransaction(
                oneMonthUser.getId(), oneMonthAccount, food.getId(), LocalDate.of(2026, 5, 2), "10", "DEBIT", "TND");
        Recommendation oneMonth = recommendationService.recommend(oneMonthUser.getId(), PLAN_MONTH, "TND");
        assertThat(oneMonth.usableMonthCount()).isEqualTo(1);
        assertThat(amountFor(oneMonth, food.getId())).isEqualByComparingTo("10.0000");

        AppUser twoMonthUser = createUser();
        UUID twoMonthAccount = insertManualAccount(twoMonthUser.getId(), "TND");
        insertManualTransaction(
                twoMonthUser.getId(), twoMonthAccount, food.getId(), LocalDate.of(2026, 5, 2), "10.0001", "DEBIT", "TND");
        insertManualTransaction(
                twoMonthUser.getId(), twoMonthAccount, housing.getId(), LocalDate.of(2026, 7, 2), "2", "DEBIT", "TND");
        Recommendation twoMonths = recommendationService.recommend(twoMonthUser.getId(), PLAN_MONTH, "TND");
        assertThat(twoMonths.usableMonthCount()).isEqualTo(2);
        assertThat(amountFor(twoMonths, food.getId())).isEqualByComparingTo("5.0000");
    }

    @Test
    void returnsZeroForEveryEligibleCategoryWhenNoUsableHistoryExists() {
        AppUser user = createUser();

        Recommendation result = recommendationService.recommend(user.getId(), PLAN_MONTH, "TND");

        assertThat(result.usableMonthCount()).isZero();
        assertThat(result.fallbackReason()).isEqualTo(FallbackReason.NO_USABLE_HISTORY);
        assertThat(result.categories()).isNotEmpty().allSatisfy(item ->
                assertThat(item.recommendedAmount()).isEqualByComparingTo("0.0000"));
    }

    private AppUser createUser() {
        UUID id = UUID.randomUUID();
        return userRepository.saveAndFlush(new AppUser(id, "History", "Tester", "History " + id));
    }

    private Category systemCategory(String code) {
        return categoryRepository.findByCodeAndUserIsNull(code).orElseThrow();
    }

    private BigDecimal amountFor(Recommendation recommendation, UUID categoryId) {
        return recommendation.categories().stream()
                .filter(item -> item.categoryId().equals(categoryId))
                .map(CategoryRecommendation::recommendedAmount)
                .findFirst()
                .orElseThrow();
    }

    private UUID insertManualAccount(UUID userId, String currency) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_account "
                        + "(id, user_id, account_origin, name, account_type, currency) "
                        + "VALUES (?, ?, 'MANUAL', 'Salary history', 'CHECKING', ?)",
                id,
                userId,
                currency);
        return id;
    }

    private void insertManualTransaction(
            UUID userId,
            UUID accountId,
            UUID categoryId,
            LocalDate bookingDate,
            String amount,
            String direction,
            String currency) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, booking_date, amount, "
                        + "direction, currency, raw_description, normalized_description, category_source, categorized_at) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', ?, ?, ?, ?, 'Salary history', "
                        + "'salary history', 'USER', CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                userId,
                accountId,
                categoryId,
                bookingDate,
                new BigDecimal(amount),
                direction,
                currency);
    }

    private UUID insertInactiveExpenseCategory(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "category "
                        + "(id, user_id, code, label, taxonomy_version, direction, nature, active) "
                        + "VALUES (?, ?, ?, 'Inactive history', 'user-v1', 'DEBIT', 'EXPENSE', FALSE)",
                id,
                userId,
                "INACTIVE_HISTORY_" + id.toString().replace("-", ""));
        return id;
    }

    private BankSyncFixture createBankSyncFixture(UUID userId, String currency) {
        UUID connectionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID syncJobId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String providerCode = "SALARY_" + connectionId.toString().substring(0, 8);
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
                "test://salary/" + authorizationId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "sync_job "
                        + "(id, bank_connection_id, authorization_id, status, started_at) "
                        + "VALUES (?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP)",
                syncJobId,
                connectionId,
                authorizationId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_account "
                        + "(id, user_id, bank_connection_id, account_origin, external_account_id, name, "
                        + "account_type, currency, provider_code) "
                        + "VALUES (?, ?, ?, 'BANK_CONNECTION', ?, 'Salary bank history', 'CHECKING', ?, ?)",
                accountId,
                userId,
                connectionId,
                "ACCOUNT-" + accountId,
                currency,
                providerCode);
        return new BankSyncFixture(userId, accountId, syncJobId, currency);
    }

    private void insertBankTransaction(
            BankSyncFixture fixture,
            UUID categoryId,
            String providerStatus,
            String amount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, sync_job_id, "
                        + "external_transaction_id, booking_date, amount, direction, currency, raw_description, "
                        + "normalized_description, category_source, categorized_at, provider_status, "
                        + "first_imported_at, last_synchronized_at) "
                        + "VALUES (?, ?, ?, ?, 'BANK_SYNC', ?, ?, DATE '2026-07-10', ?, 'DEBIT', ?, "
                        + "'Provider history', 'provider history', 'USER', CURRENT_TIMESTAMP, ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id,
                fixture.userId(),
                fixture.accountId(),
                categoryId,
                fixture.syncJobId(),
                "TX-" + id,
                new BigDecimal(amount),
                fixture.currency(),
                providerStatus);
    }

    private record BankSyncFixture(UUID userId, UUID accountId, UUID syncJobId, String currency) {
    }
}
