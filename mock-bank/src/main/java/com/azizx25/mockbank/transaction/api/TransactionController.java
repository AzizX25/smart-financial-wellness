package com.azizx25.mockbank.transaction.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.azizx25.mockbank.transaction.PaginatedTransactionResult;
import com.azizx25.mockbank.transaction.TransactionQueryService;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/accounts/{accountId}/transactions")
public class TransactionController {

    private final TransactionQueryService transactionQueryService;

    public TransactionController(TransactionQueryService transactionQueryService) {
        this.transactionQueryService = transactionQueryService;
    }

    @GetMapping
    public PaginatedTransactionResult listTransactions(
            @PathVariable("customerId") String customerId,
            @PathVariable("accountId") String accountId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return transactionQueryService.loadTransactions(
                customerId,
                accountId,
                fromDate,
                toDate,
                page,
                size);
    }
}
