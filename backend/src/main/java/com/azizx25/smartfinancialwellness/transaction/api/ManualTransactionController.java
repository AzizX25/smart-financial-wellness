package com.azizx25.smartfinancialwellness.transaction.api;

import java.util.UUID;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import com.azizx25.smartfinancialwellness.transaction.ManualTransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/transactions")
public class ManualTransactionController {
    private final ManualTransactionService service;
    public ManualTransactionController(ManualTransactionService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ManualTransactionDtos.Response create(@AuthenticationPrincipal CurrentUserPrincipal principal,
            @Valid @RequestBody ManualTransactionDtos.CreateRequest request) {
        return service.create(principal.userId(), request);
    }

    @PutMapping("/{id}")
    public ManualTransactionDtos.Response update(@AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID id, @Valid @RequestBody ManualTransactionDtos.UpdateRequest request) {
        return service.update(principal.userId(), id, request);
    }

}
