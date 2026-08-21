package com.azizx25.smartfinancialwellness.category.api;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.CategoryService;
import com.azizx25.smartfinancialwellness.category.DeterministicCategorizationService;
import com.azizx25.smartfinancialwellness.category.MerchantRecognitionPersistence;
import com.azizx25.smartfinancialwellness.category.MerchantRecognitionService;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategorizationRunResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategoryRequest;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategoryResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.CategorySummaryResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.MerchantRecognitionRunResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.MerchantSuggestionResponse;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.TransactionCategoryRequest;
import com.azizx25.smartfinancialwellness.category.api.CategoryDtos.TransactionCategoryResponse;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    public CategoryController(
            CategoryService categoryService,
            DeterministicCategorizationService categorizationService,
            MerchantRecognitionService merchantRecognitionService,
            MerchantRecognitionPersistence merchantRecognitionPersistence) {
        this.categoryService = categoryService;
        this.categorizationService = categorizationService;
        this.merchantRecognitionService = merchantRecognitionService;
        this.merchantRecognitionPersistence = merchantRecognitionPersistence;
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
}
