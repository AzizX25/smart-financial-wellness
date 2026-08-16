package com.azizx25.mockbank.auth.api;

import com.azizx25.mockbank.auth.MockBankAuthenticationService;
import com.azizx25.mockbank.auth.api.MockBankAuthDtos.LoginRequest;
import com.azizx25.mockbank.auth.api.MockBankAuthDtos.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class MockBankAuthController {

    private final MockBankAuthenticationService authenticationService;

    public MockBankAuthController(MockBankAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authenticationService.login(request.username(), request.password()));
    }
}
