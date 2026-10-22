package com.azizx25.smartfinancialwellness.bank.api;

import com.azizx25.smartfinancialwellness.bank.BankConnectionService;
import com.azizx25.smartfinancialwellness.bank.api.BankDtos.BankConnectionResponse;
import com.azizx25.smartfinancialwellness.bank.api.BankDtos.ConnectMockBankRequest;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bank-connections/mock-bank")
public class BankConnectionController {

    private final BankConnectionService connectionService;

    public BankConnectionController(BankConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @PostMapping
    public BankConnectionResponse connect(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @Valid @RequestBody ConnectMockBankRequest request) {
        return connectionService.connect(
                principal.userId(),
                request.username(),
                request.password());
    }

    @GetMapping
    public BankConnectionResponse status(
            @AuthenticationPrincipal CurrentUserPrincipal principal) {
        return connectionService.getStatus(principal.userId());
    }

    @DeleteMapping
    public ResponseEntity<Void> disconnect(
            @AuthenticationPrincipal CurrentUserPrincipal principal) {
        connectionService.disconnect(principal.userId());
        return ResponseEntity.noContent().build();
    }
}
