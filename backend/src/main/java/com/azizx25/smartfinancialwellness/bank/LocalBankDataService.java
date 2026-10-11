package com.azizx25.smartfinancialwellness.bank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.LocalAccountResponse;
import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.AccountBalanceTotalResponse;
import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.CategoryExpenseResponse;
import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.DashboardSummaryResponse;
import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.LocalTransactionPageResponse;
import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.LocalTransactionResponse;
import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.MonthlyCashFlowResponse;
import com.azizx25.smartfinancialwellness.bank.api.LocalBankDtos.MonthlyCategorySpendingResponse;
import com.azizx25.smartfinancialwellness.bank.exception.BankAccountNotFoundException;
import com.azizx25.smartfinancialwellness.bank.exception.InvalidBankRequestException;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccount;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccountRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecordRepository;
import com.azizx25.smartfinancialwellness.category.CategoryException;
import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalBankDataService {

    private final FinancialAccountRepository accountRepository;
    private final TransactionRecordRepository transactionRepository;
    private final CategoryRepository categoryRepository;

    public LocalBankDataService(
            FinancialAccountRepository accountRepository,
            TransactionRecordRepository transactionRepository,
            CategoryRepository categoryRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public List<LocalAccountResponse> listAccounts(UUID userId) {
        return accountRepository
                .findVisibleAccounts(userId)
                .stream()
                .map(this::toAccountResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public LocalTransactionPageResponse listTransactions(
            UUID userId,
            UUID accountId,
            LocalDate from,
            LocalDate to,
            TransactionRecord.Direction direction,
            UUID categoryId,
            boolean uncategorizedOnly,
            String search,
            int page,
            int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidBankRequestException("from must not be after to");
        }
        if (accountId != null) {
            accountRepository.findByIdAndUser_Id(accountId, userId)
                    .filter(account -> account.getAccountOrigin() == FinancialAccount.Origin.MANUAL
                            || account.getFirstSynchronizedAt() != null)
                    .orElseThrow(BankAccountNotFoundException::new);
        }
        if (categoryId != null) {
            categoryRepository.findVisibleActiveById(categoryId, userId)
                    .orElseThrow(CategoryException::notFound);
        }

        Page<TransactionRecord> result = transactionRepository.findLocalTransactions(
                userId,
                accountId,
                from,
                to,
                direction,
                categoryId,
                uncategorizedOnly,
                TransactionRecord.UNCATEGORIZED_CATEGORY_ID,
                normalizeSearch(search),
                PageRequest.of(
                        page,
                        size,
                        Sort.by(Sort.Order.desc("bookingDate"), Sort.Order.desc("id"))));
        return new LocalTransactionPageResponse(
                result.getContent().stream().map(this::toTransactionResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast());
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse dashboardSummary(UUID userId, YearMonth month) {
        List<AccountBalanceTotalResponse> balances = accountRepository.summarizeBalances(userId)
                .stream()
                .map(total -> new AccountBalanceTotalResponse(
                        total.getCurrency(),
                        total.getTotalBalance(),
                        total.getAccountCount()))
                .toList();
        List<MonthlyCashFlowResponse> cashFlows = transactionRepository
                .summarizeMonthlyCashFlow(userId, month.atDay(1), month.plusMonths(1).atDay(1))
                .stream()
                .map(total -> new MonthlyCashFlowResponse(
                        total.getCurrency(),
                        total.getIncome(),
                        total.getExpenses(),
                        total.getIncome().subtract(total.getExpenses())))
                .toList();
        Map<String, MutableCategorySpending> spendingByCurrency = new LinkedHashMap<>();
        transactionRepository.summarizeMonthlyCategoryExpenses(
                        userId,
                        month.atDay(1),
                        month.plusMonths(1).atDay(1),
                        TransactionRecord.UNCATEGORIZED_CATEGORY_ID)
                .forEach(total -> {
                    MutableCategorySpending spending = spendingByCurrency.computeIfAbsent(
                            total.getCurrency(), ignored -> new MutableCategorySpending());
                    spending.totalExpenses = spending.totalExpenses.add(total.getTotalAmount());
                    if (TransactionRecord.UNCATEGORIZED_CATEGORY_ID.equals(total.getCategoryId())) {
                        spending.uncategorizedCount = total.getTransactionCount();
                        spending.uncategorizedAmount = total.getTotalAmount();
                    } else {
                        spending.categories.add(new CategoryExpenseResponse(
                                total.getCategoryId(),
                                total.getCategoryName(),
                                total.getTransactionCount(),
                                total.getTotalAmount()));
                    }
                });
        List<MonthlyCategorySpendingResponse> categorySpending = spendingByCurrency.entrySet().stream()
                .map(entry -> new MonthlyCategorySpendingResponse(
                        entry.getKey(),
                        entry.getValue().totalExpenses,
                        entry.getValue().categories,
                        entry.getValue().uncategorizedCount,
                        entry.getValue().uncategorizedAmount))
                .toList();
        return new DashboardSummaryResponse(month.toString(), balances, cashFlows, categorySpending);
    }

    private String normalizeSearch(String search) {
        if (search == null || search.isBlank()) {
            return "";
        }
        return search.trim();
    }

    private LocalAccountResponse toAccountResponse(FinancialAccount account) {
        return new LocalAccountResponse(
                account.getId(),
                account.getProviderCode(),
                account.getName(),
                account.getAccountType(),
                account.getMaskedIdentifier(),
                account.getCurrency(),
                account.getCurrentBalance(),
                account.getAvailableBalance(),
                account.getProviderAccountStatus(),
                account.getStatus().name(),
                account.getFirstSynchronizedAt(),
                account.getLastSynchronizedAt(),
                account.getAccountOrigin().name());
    }

    private LocalTransactionResponse toTransactionResponse(TransactionRecord transaction) {
        Category category = transaction.getCategory();
        boolean uncategorized = TransactionRecord.UNCATEGORIZED_CATEGORY_ID.equals(category.getId());
        return new LocalTransactionResponse(
                transaction.getId(),
                transaction.getAccountId(),
                transaction.getBookingDate(),
                transaction.getValueDate(),
                transaction.getDescription(),
                transaction.getRecognizedMerchant(),
                transaction.getDirection().name(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getProviderStatus(),
                uncategorized ? null : category.getId(),
                uncategorized ? null : category.getLabel(),
                uncategorized ? null : category.getNature().name(),
                switch (transaction.getCategorySource()) {
                    case "USER" -> "MANUAL";
                    case "RULES" -> "DETERMINISTIC_RULE";
                    case "ML" -> "ML";
                    case "LLM" -> "WEB_LLM";
                    case "USER_MEMORY" -> "USER_MEMORY";
                    default -> "UNCATEGORIZED";
                },
                transaction.getCategorizedAt(),
                transaction.getFirstImportedAt(),
                transaction.getLastSynchronizedAt());
    }

    private static final class MutableCategorySpending {
        private BigDecimal totalExpenses = new BigDecimal("0.0000");
        private final List<CategoryExpenseResponse> categories = new ArrayList<>();
        private long uncategorizedCount;
        private BigDecimal uncategorizedAmount = new BigDecimal("0.0000");
    }
}
