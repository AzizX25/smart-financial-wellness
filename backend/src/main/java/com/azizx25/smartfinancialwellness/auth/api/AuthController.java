package com.azizx25.smartfinancialwellness.auth.api;

import com.azizx25.smartfinancialwellness.auth.AuthService;
import com.azizx25.smartfinancialwellness.auth.RefreshCookieService;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieService refreshCookieService;

    public AuthController(AuthService authService, RefreshCookieService refreshCookieService) {
        this.authService = authService;
        this.refreshCookieService = refreshCookieService;
    }

    @PostMapping("/register")
    ResponseEntity<AuthDtos.AccessTokenResponse> register(
            @Valid @RequestBody AuthDtos.RegisterRequest request) {
        AuthService.AuthResult result = authService.register(request);
        return withRefreshCookie(result, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    ResponseEntity<AuthDtos.AccessTokenResponse> login(
            @Valid @RequestBody AuthDtos.LoginRequest request) {
        AuthService.AuthResult result = authService.login(request);
        return withRefreshCookie(result, HttpStatus.OK);
    }

    @PostMapping("/refresh")
    ResponseEntity<AuthDtos.AccessTokenResponse> refresh(
            @CookieValue(name = RefreshCookieService.COOKIE_NAME, required = false) String refreshToken) {
        AuthService.AuthResult result = authService.refresh(refreshToken);
        return withRefreshCookie(result, HttpStatus.OK);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookieService.COOKIE_NAME, required = false) String refreshToken,
            @AuthenticationPrincipal CurrentUserPrincipal principal) {
        authService.logout(refreshToken, principal.identityId(), principal.userId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookieService.clear().toString())
                .build();
    }

    @GetMapping("/me")
    AuthDtos.UserResponse me(@AuthenticationPrincipal CurrentUserPrincipal principal) {
        return authService.currentUser(principal.userId());
    }

    private ResponseEntity<AuthDtos.AccessTokenResponse> withRefreshCookie(
            AuthService.AuthResult result,
            HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookieService.create(result.rawRefreshToken()).toString())
                .body(result.response());
    }
}
