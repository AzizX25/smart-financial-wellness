package com.azizx25.smartfinancialwellness.backend.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.backend.audit.AuditService;
import com.azizx25.smartfinancialwellness.backend.auth.api.AuthDtos;
import com.azizx25.smartfinancialwellness.backend.auth.persistence.AuthenticationIdentity;
import com.azizx25.smartfinancialwellness.backend.auth.persistence.AuthenticationIdentityRepository;
import com.azizx25.smartfinancialwellness.backend.auth.persistence.PasswordCredential;
import com.azizx25.smartfinancialwellness.backend.auth.persistence.PasswordCredentialRepository;
import com.azizx25.smartfinancialwellness.backend.common.web.ApiException;
import com.azizx25.smartfinancialwellness.backend.security.JwtService;
import com.azizx25.smartfinancialwellness.backend.security.SecurityProperties;
import com.azizx25.smartfinancialwellness.backend.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.backend.user.persistence.AppUserRepository;
import com.azizx25.smartfinancialwellness.backend.user.persistence.FinancialPreference;
import com.azizx25.smartfinancialwellness.backend.user.persistence.FinancialPreferenceRepository;
import com.azizx25.smartfinancialwellness.backend.user.persistence.InterfacePreference;
import com.azizx25.smartfinancialwellness.backend.user.persistence.InterfacePreferenceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AuthenticationIdentityRepository identityRepository;
    private final PasswordCredentialRepository credentialRepository;
    private final AppUserRepository userRepository;
    private final InterfacePreferenceRepository interfacePreferenceRepository;
    private final FinancialPreferenceRepository financialPreferenceRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final SecurityProperties properties;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(
            AuthenticationIdentityRepository identityRepository,
            PasswordCredentialRepository credentialRepository,
            AppUserRepository userRepository,
            InterfacePreferenceRepository interfacePreferenceRepository,
            FinancialPreferenceRepository financialPreferenceRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService,
            JwtService jwtService,
            AuditService auditService,
            SecurityProperties properties,
            Clock clock) {
        this.identityRepository = identityRepository;
        this.credentialRepository = credentialRepository;
        this.userRepository = userRepository;
        this.interfacePreferenceRepository = interfacePreferenceRepository;
        this.financialPreferenceRepository = financialPreferenceRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-value");
    }

    @Transactional
    public AuthResult register(AuthDtos.RegisterRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        if (identityRepository.existsByIdentityTypeAndNormalizedIdentifier(
                AuthenticationIdentity.Type.EMAIL,
                normalizedEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "An account already exists for this email address");
        }

        UUID userId = UUID.randomUUID();
        AppUser newUser = new AppUser(
                userId,
                normalizeOptionalName(request.firstName()),
                normalizeOptionalName(request.lastName()),
                request.displayName().strip());
        AuthenticationIdentity identity;
        try {
            AppUser user = userRepository.saveAndFlush(newUser);
            interfacePreferenceRepository.save(new InterfacePreference(user));
            financialPreferenceRepository.save(new FinancialPreference(user));
            identity = new AuthenticationIdentity(UUID.randomUUID(), user, normalizedEmail);
            identityRepository.saveAndFlush(identity);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "An account already exists for this email address");
        }
        credentialRepository.save(new PasswordCredential(
                identity,
                passwordEncoder.encode(request.password()),
                properties.bcryptStrength()));
        auditService.record(userId, "AUTH_REGISTER", "APP_USER", userId);
        return issueTokens(identity);
    }

    @Transactional
    public AuthResult login(AuthDtos.LoginRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        AuthenticationIdentity identity = identityRepository
                .findByIdentityTypeAndNormalizedIdentifier(AuthenticationIdentity.Type.EMAIL, normalizedEmail)
                .orElse(null);
        PasswordCredential credential = identity == null
                ? null
                : credentialRepository.findById(identity.getId()).orElse(null);
        String storedHash = credential == null ? dummyPasswordHash : credential.getPasswordHash();
        boolean matches = passwordEncoder.matches(request.password(), storedHash);
        if (!matches || identity == null || credential == null || !isAuthenticationAllowed(identity)) {
            throw invalidCredentials();
        }
        auditService.record(identity.getUser().getId(), "AUTH_LOGIN", "AUTHENTICATION_IDENTITY", identity.getId());
        return issueTokens(identity);
    }

    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public AuthResult refresh(String rawRefreshToken) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.rotate(rawRefreshToken);
        AuthenticationIdentity identity = refreshToken.identity();
        JwtService.IssuedAccessToken accessToken = jwtService.issue(identity.getUser().getId());
        auditService.record(identity.getUser().getId(), "AUTH_REFRESH", "REFRESH_SESSION", refreshToken.session().getId());
        return AuthResult.of(accessToken, refreshToken, identity, clock);
    }

    @Transactional
    public void logout(String rawRefreshToken, UUID identityId, UUID userId) {
        refreshTokenService.revoke(rawRefreshToken, identityId);
        auditService.record(userId, "AUTH_LOGOUT", "AUTHENTICATION_IDENTITY", identityId);
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserResponse currentUser(UUID userId) {
        return identityRepository.findByUserId(userId)
                .map(AuthDtos.UserResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Authentication is required"));
    }

    private AuthResult issueTokens(AuthenticationIdentity identity) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.create(identity);
        JwtService.IssuedAccessToken accessToken = jwtService.issue(identity.getUser().getId());
        return AuthResult.of(accessToken, refreshToken, identity, clock);
    }

    private boolean isAuthenticationAllowed(AuthenticationIdentity identity) {
        AppUser.Status userStatus = identity.getUser().getStatus();
        return identity.getStatus() != AuthenticationIdentity.Status.DISABLED
                && userStatus != AppUser.Status.SUSPENDED
                && userStatus != AppUser.Status.DELETION_PENDING
                && userStatus != AppUser.Status.DELETED;
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect");
    }

    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private String normalizeOptionalName(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Names must not be blank when provided");
        }
        return normalized;
    }

    public record AuthResult(
            AuthDtos.AccessTokenResponse response,
            String rawRefreshToken) {

        private static AuthResult of(
                JwtService.IssuedAccessToken accessToken,
                RefreshTokenService.IssuedRefreshToken refreshToken,
                AuthenticationIdentity identity,
                Clock clock) {
            long expiresIn = Duration.between(clock.instant(), accessToken.expiresAt()).toSeconds();
            return new AuthResult(
                    new AuthDtos.AccessTokenResponse(
                            accessToken.value(),
                            "Bearer",
                            expiresIn,
                            AuthDtos.UserResponse.from(identity)),
                    refreshToken.value());
        }
    }
}
