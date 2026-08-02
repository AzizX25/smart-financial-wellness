package com.azizx25.mockbank.transaction;

import java.util.List;

public record PaginatedTransactionResult(
        List<TransactionSummary> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {
}
