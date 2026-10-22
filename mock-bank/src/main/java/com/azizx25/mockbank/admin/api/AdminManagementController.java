package com.azizx25.mockbank.admin.api;

import java.time.LocalDate;

import com.azizx25.mockbank.admin.AdminManagementService;
import com.azizx25.mockbank.admin.api.AdminDtos.AccountResponse;
import com.azizx25.mockbank.admin.api.AdminDtos.CreateAccountRequest;
import com.azizx25.mockbank.admin.api.AdminDtos.CreateCustomerRequest;
import com.azizx25.mockbank.admin.api.AdminDtos.CreateTransactionRequest;
import com.azizx25.mockbank.admin.api.AdminDtos.CustomerResponse;
import com.azizx25.mockbank.admin.api.AdminDtos.DashboardResponse;
import com.azizx25.mockbank.admin.api.AdminDtos.PageResponse;
import com.azizx25.mockbank.admin.api.AdminDtos.ResetPasswordRequest;
import com.azizx25.mockbank.admin.api.AdminDtos.TransactionResponse;
import com.azizx25.mockbank.admin.api.AdminDtos.UpdateAccountRequest;
import com.azizx25.mockbank.admin.api.AdminDtos.UpdateCustomerRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminManagementController {

    private final AdminManagementService managementService;

    public AdminManagementController(AdminManagementService managementService) {
        this.managementService = managementService;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return managementService.dashboard();
    }

    @GetMapping("/customers")
    public PageResponse<CustomerResponse> listCustomers(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return managementService.listCustomers(search, page, size);
    }

    @GetMapping("/customers/{customerId}")
    public CustomerResponse getCustomer(@PathVariable String customerId) {
        return managementService.getCustomer(customerId);
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        return managementService.createCustomer(request);
    }

    @PatchMapping("/customers/{customerId}")
    public CustomerResponse updateCustomer(
            @PathVariable String customerId,
            @Valid @RequestBody UpdateCustomerRequest request) {
        return managementService.updateCustomer(customerId, request);
    }

    @PostMapping("/customers/{customerId}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(
            @PathVariable String customerId,
            @Valid @RequestBody ResetPasswordRequest request) {
        managementService.resetCustomerPassword(customerId, request.password());
    }

    @PostMapping("/customers/{customerId}/activate")
    public CustomerResponse activateCustomer(@PathVariable String customerId) {
        return managementService.activateCustomer(customerId);
    }

    @PostMapping("/customers/{customerId}/deactivate")
    public CustomerResponse deactivateCustomer(@PathVariable String customerId) {
        return managementService.deactivateCustomer(customerId);
    }

    @GetMapping("/accounts")
    public PageResponse<AccountResponse> listAccounts(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String accountType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return managementService.listAccounts(customerId, status, accountType, page, size);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountResponse getAccount(@PathVariable String accountId) {
        return managementService.getAccount(accountId);
    }

    @PostMapping("/customers/{customerId}/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(
            @PathVariable String customerId,
            @Valid @RequestBody CreateAccountRequest request) {
        return managementService.createAccount(customerId, request);
    }

    @PatchMapping("/accounts/{accountId}")
    public AccountResponse updateAccount(
            @PathVariable String accountId,
            @Valid @RequestBody UpdateAccountRequest request) {
        return managementService.updateAccount(accountId, request);
    }

    @PostMapping("/accounts/{accountId}/activate")
    public AccountResponse activateAccount(@PathVariable String accountId) {
        return managementService.activateAccount(accountId);
    }

    @PostMapping("/accounts/{accountId}/freeze")
    public AccountResponse freezeAccount(@PathVariable String accountId) {
        return managementService.freezeAccount(accountId);
    }

    @PostMapping("/accounts/{accountId}/close")
    public AccountResponse closeAccount(@PathVariable String accountId) {
        return managementService.closeAccount(accountId);
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public PageResponse<TransactionResponse> listTransactions(
            @PathVariable String accountId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return managementService.listTransactions(accountId, from, to, page, size);
    }

    @PostMapping("/accounts/{accountId}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody CreateTransactionRequest request) {
        return managementService.createTransaction(accountId, request);
    }
}
