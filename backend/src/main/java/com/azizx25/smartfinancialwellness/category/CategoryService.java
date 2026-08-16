package com.azizx25.smartfinancialwellness.category;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecordRepository;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategoryRequest;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategoryResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategorySummaryResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategoryTotal;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CurrencyCategorySummary;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.TransactionCategoryResponse;
import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.category.persistence.TransactionCategoryHistory;
import com.azizx25.smartfinancialwellness.category.persistence.TransactionCategoryHistoryRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final TransactionRecordRepository transactionRepository;
    private final TransactionCategoryHistoryRepository historyRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;

    public CategoryService(
            CategoryRepository categoryRepository,
            TransactionRecordRepository transactionRepository,
            TransactionCategoryHistoryRepository historyRepository,
            AppUserRepository userRepository,
            AuditService auditService,
            Clock clock) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(UUID userId) {
        return categoryRepository.findVisibleActive(userId).stream().map(CategoryResponse::from).toList();
    }

    @Transactional
    public CategoryResponse create(UUID userId, CategoryRequest request) {
        String name = normalizeName(request.name());
        if (categoryRepository.existsByUser_IdAndLabelIgnoreCase(userId, name)) {
            throw CategoryException.duplicate();
        }
        AppUser user = userRepository.getReferenceById(userId);
        try {
            Category category = categoryRepository.saveAndFlush(new Category(
                    user, name, request.nature(), emptyToNull(request.iconKey()), request.color(), clock.instant()));
            auditService.record(userId, "PERSONAL_CATEGORY_CREATED", "CATEGORY", category.getId());
            return CategoryResponse.from(category);
        } catch (DataIntegrityViolationException exception) {
            throw CategoryException.duplicate();
        }
    }

    @Transactional
    public CategoryResponse update(UUID userId, UUID categoryId, CategoryRequest request) {
        Category category = ownedCategory(userId, categoryId);
        String name = normalizeName(request.name());
        if (categoryRepository.existsByUser_IdAndLabelIgnoreCaseAndIdNot(userId, name, categoryId)) {
            throw CategoryException.duplicate();
        }
        if (category.getNature() != request.nature() && categoryRepository.isReferenced(categoryId)) {
            throw CategoryException.inUse();
        }
        category.update(name, request.nature(), emptyToNull(request.iconKey()), request.color());
        try {
            categoryRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw CategoryException.duplicate();
        }
        auditService.record(userId, "PERSONAL_CATEGORY_UPDATED", "CATEGORY", categoryId);
        return CategoryResponse.from(category);
    }

    @Transactional
    public void delete(UUID userId, UUID categoryId) {
        Category category = categoryRepository.findById(categoryId).orElseThrow(CategoryException::notFound);
        if (category.getUserId() == null) {
            throw CategoryException.readOnly();
        }
        if (!userId.equals(category.getUserId())) {
            throw CategoryException.notFound();
        }
        if (categoryRepository.isReferenced(categoryId)) {
            throw CategoryException.inUse();
        }
        try {
            categoryRepository.delete(category);
            categoryRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw CategoryException.inUse();
        }
        auditService.record(userId, "PERSONAL_CATEGORY_DELETED", "CATEGORY", categoryId);
    }

    @Transactional
    public TransactionCategoryResponse assign(UUID userId, UUID transactionId, UUID categoryId) {
        TransactionRecord transaction = transactionRepository
                .findOwnedByIdForUpdate(transactionId, userId)
                .orElseThrow(CategoryException::transactionNotFound);
        Category previous = transaction.getCategory();
        String previousSource = transaction.getCategorySource();
        Category target;
        String newSource;
        Instant categorizedAt;
        boolean removing = categoryId == null
                || TransactionRecord.UNCATEGORIZED_CATEGORY_ID.equals(categoryId);
        if (removing) {
            target = categoryRepository.getReferenceById(TransactionRecord.UNCATEGORIZED_CATEGORY_ID);
            newSource = "UNCATEGORIZED";
            categorizedAt = null;
        } else {
            target = categoryRepository.findVisibleActiveById(categoryId, userId)
                    .orElseThrow(CategoryException::notFound);
            validateDirection(transaction, target);
            newSource = "USER";
            categorizedAt = clock.instant();
        }

        if (previous.getId().equals(target.getId()) && previousSource.equals(newSource)) {
            return response(transaction);
        }

        if (removing) {
            transaction.removeCategory(target);
        } else {
            transaction.assignManually(target, categorizedAt);
        }
        historyRepository.save(new TransactionCategoryHistory(
                transaction,
                visibleCategoryId(previous),
                visibleCategoryId(target),
                previousSource,
                newSource,
                null,
                transaction.getUser(),
                clock.instant()));
        auditService.record(userId, "TRANSACTION_CATEGORY_CHANGED", "TRANSACTION", transactionId);
        return response(transaction);
    }

    @Transactional(readOnly = true)
    public CategorySummaryResponse summary(UUID userId, YearMonth month) {
        Map<String, MutableCurrencySummary> currencies = new LinkedHashMap<>();
        categoryRepository.summarize(userId, month.atDay(1), month.plusMonths(1).atDay(1))
                .forEach(row -> {
                    MutableCurrencySummary currency = currencies.computeIfAbsent(
                            row.getCurrency(), ignored -> new MutableCurrencySummary());
                    if (TransactionRecord.UNCATEGORIZED_CATEGORY_ID.equals(row.getCategoryId())) {
                        currency.uncategorizedCount = row.getTransactionCount();
                        currency.uncategorizedAmount = row.getTotalAmount();
                    } else {
                        currency.categories.add(new CategoryTotal(
                                row.getCategoryId(), row.getCategoryLabel(), row.getCategoryNature(),
                                row.getTransactionCount(), row.getTotalAmount()));
                    }
                });
        List<CurrencyCategorySummary> summaries = currencies.entrySet().stream()
                .map(entry -> new CurrencyCategorySummary(
                        entry.getKey(), entry.getValue().categories, entry.getValue().uncategorizedCount,
                        entry.getValue().uncategorizedAmount))
                .toList();
        return new CategorySummaryResponse(month.toString(), summaries);
    }

    private Category ownedCategory(UUID userId, UUID categoryId) {
        Category category = categoryRepository.findById(categoryId).orElseThrow(CategoryException::notFound);
        if (category.getUserId() == null) throw CategoryException.readOnly();
        if (!userId.equals(category.getUserId())) throw CategoryException.notFound();
        return category;
    }

    private void validateDirection(TransactionRecord transaction, Category category) {
        if (category.getDirection() != Category.Direction.BOTH
                && !category.getDirection().name().equals(transaction.getDirection().name())) {
            throw CategoryException.notAllowed();
        }
    }

    private TransactionCategoryResponse response(TransactionRecord transaction) {
        Category category = transaction.getCategory();
        boolean uncategorized = TransactionRecord.UNCATEGORIZED_CATEGORY_ID.equals(category.getId());
        return new TransactionCategoryResponse(
                transaction.getId(), uncategorized ? null : category.getId(),
                uncategorized ? null : category.getLabel(),
                uncategorized ? null : category.getNature().name(),
                externalSource(transaction.getCategorySource()), transaction.getCategorizedAt());
    }

    private UUID visibleCategoryId(Category category) {
        return TransactionRecord.UNCATEGORIZED_CATEGORY_ID.equals(category.getId()) ? null : category.getId();
    }

    private String externalSource(String source) {
        return switch (source) {
            case "USER" -> "MANUAL";
            case "RULES" -> "DETERMINISTIC_RULE";
            default -> "UNCATEGORIZED";
        };
    }

    private String normalizeName(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class MutableCurrencySummary {
        private final List<CategoryTotal> categories = new ArrayList<>();
        private long uncategorizedCount;
        private BigDecimal uncategorizedAmount = BigDecimal.ZERO;
    }
}
