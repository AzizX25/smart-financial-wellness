package com.azizx25.mockbank.admin.api;

import com.azizx25.mockbank.admin.AdminAuthenticationService;
import com.azizx25.mockbank.admin.api.AdminDtos.LoginRequest;
import com.azizx25.mockbank.admin.api.AdminDtos.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthenticationService authenticationService;

    public AdminAuthController(AdminAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authenticationService.login(request.username(), request.password()));
    }
}
