package com.azizx25.smartfinancialwellness.bank.model;

import java.util.List;

public record ExternalBankTransactionPage(
        List<ExternalBankTransaction> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public ExternalBankTransactionPage {
        items = List.copyOf(items);
    }
}
