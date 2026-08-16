package com.azizx25.smartfinancialwellness.bank.api;

import com.azizx25.smartfinancialwellness.bank.BankSynchronizationService;
import com.azizx25.smartfinancialwellness.bank.api.BankSynchronizationDtos.SynchronizationStatusResponse;
import com.azizx25.smartfinancialwellness.bank.api.BankSynchronizationDtos.SynchronizationSummary;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bank/synchronizations")
public class BankSynchronizationController {

    private final BankSynchronizationService synchronizationService;

    public BankSynchronizationController(BankSynchronizationService synchronizationService) {
        this.synchronizationService = synchronizationService;
    }

    @PostMapping
    public SynchronizationSummary synchronize(
            @AuthenticationPrincipal CurrentUserPrincipal principal) {
        return synchronizationService.synchronize(principal.userId());
    }

    @GetMapping("/status")
    public SynchronizationStatusResponse status(
            @AuthenticationPrincipal CurrentUserPrincipal principal) {
        return synchronizationService.getStatus(principal.userId());
    }
}
