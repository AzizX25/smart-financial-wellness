package com.azizx25.smartfinancialwellness.auth.api;

import com.azizx25.smartfinancialwellness.auth.AuthService;
import com.azizx25.smartfinancialwellness.auth.AccountActionService;
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
    private final AccountActionService accountActionService;
    private final RefreshCookieService refreshCookieService;

    public AuthController(
            AuthService authService,
            AccountActionService accountActionService,
            RefreshCookieService refreshCookieService) {
        this.authService = authService;
        this.accountActionService = accountActionService;
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

    @PostMapping("/email-verification/confirm")
    AuthDtos.MessageResponse confirmEmailVerification(
            @Valid @RequestBody AuthDtos.TokenRequest request) {
        accountActionService.confirmEmail(request.token());
        return new AuthDtos.MessageResponse("Email verification completed");
    }

    @PostMapping("/email-verification/resend")
    ResponseEntity<AuthDtos.MessageResponse> resendEmailVerification(
            @Valid @RequestBody AuthDtos.EmailRequest request) {
        accountActionService.resendVerification(request.email());
        return ResponseEntity.accepted().body(new AuthDtos.MessageResponse(
                "If the account is eligible, a verification email will be sent"));
    }

    @PostMapping("/password-reset/request")
    ResponseEntity<AuthDtos.MessageResponse> requestPasswordReset(
            @Valid @RequestBody AuthDtos.EmailRequest request) {
        accountActionService.requestPasswordReset(request.email());
        return ResponseEntity.accepted().body(new AuthDtos.MessageResponse(
                "If the account is eligible, a password-reset email will be sent"));
    }

    @PostMapping("/password-reset/confirm")
    AuthDtos.MessageResponse confirmPasswordReset(
            @Valid @RequestBody AuthDtos.PasswordResetConfirmRequest request) {
        accountActionService.confirmPasswordReset(request.token(), request.newPassword());
        return new AuthDtos.MessageResponse("Password reset completed");
    }

    private ResponseEntity<AuthDtos.AccessTokenResponse> withRefreshCookie(
            AuthService.AuthResult result,
            HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookieService.create(result.rawRefreshToken()).toString())
                .body(result.response());
    }
}
