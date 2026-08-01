package com.azizx25.mockbank.transaction;

import java.util.List;

public record PaginatedTransactionResult(
        List<TransactionSummary> transactions,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {
}