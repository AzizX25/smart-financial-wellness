package com.azizx25.smartfinancialwellness.category.api;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.CategoryService;
import com.azizx25.smartfinancialwellness.category.CategorizationReviewService;
import com.azizx25.smartfinancialwellness.category.DeterministicCategorizationService;
import com.azizx25.smartfinancialwellness.category.MerchantRecognitionPersistence;
import com.azizx25.smartfinancialwellness.category.MerchantRecognitionService;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategorizationRunResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategorizationReviewPageResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategoryRequest;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategoryResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategorySummaryResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.MerchantRecognitionRunResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.MerchantSuggestionResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.MerchantMemoryResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.TransactionCategoryRequest;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.TransactionCategoryResponse;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import com.azizx25.smartfinancialwellness.common.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api")
public class CategoryController {

    private final CategoryService categoryService;
    private final DeterministicCategorizationService categorizationService;
    private final MerchantRecognitionService merchantRecognitionService;
    private final MerchantRecognitionPersistence merchantRecognitionPersistence;
    private final CategorizationReviewService reviewService;

    public CategoryController(
            CategoryService categoryService,
            DeterministicCategorizationService categorizationService,
            MerchantRecognitionService merchantRecognitionService,
            MerchantRecognitionPersistence merchantRecognitionPersistence,
            CategorizationReviewService reviewService) {
        this.categoryService = categoryService;
        this.categorizationService = categorizationService;
        this.merchantRecognitionService = merchantRecognitionService;
        this.merchantRecognitionPersistence = merchantRecognitionPersistence;
        this.reviewService = reviewService;
    }

    @GetMapping("/categories")
    public List<CategoryResponse> list(@AuthenticationPrincipal CurrentUserPrincipal principal) {
        return categoryService.list(principal.userId());
    }

    @PostMapping("/categories")
    public ResponseEntity<CategoryResponse> create(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(principal.userId(), request));
    }

    @PatchMapping("/categories/{categoryId}")
    public CategoryResponse update(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID categoryId,
            @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(principal.userId(), categoryId, request);
    }

    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID categoryId) {
        categoryService.delete(principal.userId(), categoryId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/transactions/{transactionId}/category")
    public TransactionCategoryResponse assign(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID transactionId,
            @RequestBody TransactionCategoryRequest request) {
        return categoryService.assign(principal.userId(), transactionId, request.categoryId());
    }

    @PostMapping("/categorization-runs")
    public CategorizationRunResponse run(@AuthenticationPrincipal CurrentUserPrincipal principal) {
        return categorizationService.categorizeUncategorized(principal.userId());
    }

    @PostMapping("/categorization-runs/merchant-recognition")
    public MerchantRecognitionRunResponse runMerchantRecognition(
            @AuthenticationPrincipal CurrentUserPrincipal principal) {
        return merchantRecognitionService.generate(principal.userId());
    }

    @GetMapping("/merchant-suggestions")
    public List<MerchantSuggestionResponse> pendingMerchantSuggestions(
            @AuthenticationPrincipal CurrentUserPrincipal principal) {
        return merchantRecognitionPersistence.listPending(principal.userId());
    }

    @GetMapping("/categorization-reviews")
    public CategorizationReviewPageResponse reviews(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewService.list(principal.userId(), page, size);
    }

    @GetMapping("/merchant-memories")
    public List<MerchantMemoryResponse> merchantMemories(
            @AuthenticationPrincipal CurrentUserPrincipal principal) {
        return reviewService.listMemories(principal.userId());
    }

    @DeleteMapping("/merchant-memories/{memoryId}")
    public ResponseEntity<Void> forgetMerchantMemory(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID memoryId) {
        reviewService.forget(principal.userId(), memoryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/transactions/{transactionId}/merchant-suggestions/{predictionId}/accept")
    public TransactionCategoryResponse acceptMerchantSuggestion(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID transactionId,
            @PathVariable UUID predictionId) {
        return merchantRecognitionPersistence.accept(principal.userId(), transactionId, predictionId);
    }

    @PostMapping("/transactions/{transactionId}/merchant-suggestions/{predictionId}/reject")
    public ResponseEntity<Void> rejectMerchantSuggestion(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID transactionId,
            @PathVariable UUID predictionId) {
        merchantRecognitionPersistence.reject(principal.userId(), transactionId, predictionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/categories/summary")
    public CategorySummaryResponse summary(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String month) {
        return categoryService.summary(principal.userId(), YearMonth.parse(month));
    }

    @ExceptionHandler({
            DataIntegrityViolationException.class,
            ObjectOptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class
    })
    ResponseEntity<ApiError> categorizationConflict(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        HttpStatus.CONFLICT,
                        "CATEGORIZATION_CONFLICT",
                        "Categorization state changed concurrently",
                        request.getRequestURI()));
    }
}
