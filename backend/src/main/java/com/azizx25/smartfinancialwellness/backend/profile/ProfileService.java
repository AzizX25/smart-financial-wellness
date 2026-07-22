package com.azizx25.smartfinancialwellness.backend.profile;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.backend.audit.AuditService;
import com.azizx25.smartfinancialwellness.backend.auth.persistence.AuthenticationIdentity;
import com.azizx25.smartfinancialwellness.backend.auth.persistence.AuthenticationIdentityRepository;
import com.azizx25.smartfinancialwellness.backend.common.web.ApiException;
import com.azizx25.smartfinancialwellness.backend.profile.api.ProfileDtos;
import com.azizx25.smartfinancialwellness.backend.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.backend.user.persistence.FinancialPreference;
import com.azizx25.smartfinancialwellness.backend.user.persistence.FinancialPreferenceRepository;
import com.azizx25.smartfinancialwellness.backend.user.persistence.InterfacePreference;
import com.azizx25.smartfinancialwellness.backend.user.persistence.InterfacePreferenceRepository;
import com.azizx25.smartfinancialwellness.backend.user.persistence.NotificationPreference;
import com.azizx25.smartfinancialwellness.backend.user.persistence.NotificationPreferenceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private final AuthenticationIdentityRepository identityRepository;
    private final InterfacePreferenceRepository interfacePreferenceRepository;
    private final FinancialPreferenceRepository financialPreferenceRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final AuditService auditService;

    public ProfileService(
            AuthenticationIdentityRepository identityRepository,
            InterfacePreferenceRepository interfacePreferenceRepository,
            FinancialPreferenceRepository financialPreferenceRepository,
            NotificationPreferenceRepository notificationPreferenceRepository,
            AuditService auditService) {
        this.identityRepository = identityRepository;
        this.interfacePreferenceRepository = interfacePreferenceRepository;
        this.financialPreferenceRepository = financialPreferenceRepository;
        this.notificationPreferenceRepository = notificationPreferenceRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public ProfileDtos.ProfileResponse get(UUID userId) {
        return loadProfile(userId);
    }

    @Transactional
    public ProfileDtos.ProfileResponse update(UUID userId, ProfileDtos.UpdateProfileRequest request) {
        validateLanguageTag(request.interfacePreferences().languageTag());
        validateTimeZone(request.interfacePreferences().timeZone());

        AuthenticationIdentity identity = requireIdentity(userId);
        AppUser user = identity.getUser();
        InterfacePreference interfacePreference = interfacePreferenceRepository.findById(userId)
                .orElseThrow(this::incompleteProfile);
        FinancialPreference financialPreference = financialPreferenceRepository.findById(userId)
                .orElseThrow(this::incompleteProfile);

        user.setFirstName(normalizeOptionalName(request.firstName()));
        user.setLastName(normalizeOptionalName(request.lastName()));
        user.setDisplayName(request.displayName().strip());

        ProfileDtos.InterfacePreferencesRequest interfaceRequest = request.interfacePreferences();
        interfacePreference.setLanguageTag(interfaceRequest.languageTag().strip());
        interfacePreference.setTimeZone(interfaceRequest.timeZone().strip());
        interfacePreference.setTheme(interfaceRequest.theme());
        interfacePreference.setTextSize(interfaceRequest.textSize());
        interfacePreference.setHighContrast(interfaceRequest.highContrast());
        interfacePreference.setReducedMotion(interfaceRequest.reducedMotion());

        ProfileDtos.FinancialPreferencesRequest financialRequest = request.financialPreferences();
        financialPreference.setPrimaryCurrency(financialRequest.primaryCurrency());
        financialPreference.setMonthStartDay(financialRequest.monthStartDay());
        financialPreference.setLowBalanceThreshold(financialRequest.lowBalanceThreshold());
        financialPreference.setHideHomeAmounts(financialRequest.hideHomeAmounts());

        auditService.record(userId, "PROFILE_UPDATE", "APP_USER", userId);
        return toResponse(identity, interfacePreference, financialPreference, notificationPreferences(userId));
    }

    private ProfileDtos.ProfileResponse loadProfile(UUID userId) {
        AuthenticationIdentity identity = requireIdentity(userId);
        InterfacePreference interfacePreference = interfacePreferenceRepository.findById(userId)
                .orElseThrow(this::incompleteProfile);
        FinancialPreference financialPreference = financialPreferenceRepository.findById(userId)
                .orElseThrow(this::incompleteProfile);
        return toResponse(identity, interfacePreference, financialPreference, notificationPreferences(userId));
    }

    private AuthenticationIdentity requireIdentity(UUID userId) {
        return identityRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Authentication is required"));
    }

    private List<NotificationPreference> notificationPreferences(UUID userId) {
        return notificationPreferenceRepository.findAllByUserIdOrderByEventTypeAscChannelAsc(userId);
    }

    private ProfileDtos.ProfileResponse toResponse(
            AuthenticationIdentity identity,
            InterfacePreference interfacePreference,
            FinancialPreference financialPreference,
            List<NotificationPreference> notifications) {
        AppUser user = identity.getUser();
        return new ProfileDtos.ProfileResponse(
                user.getId(),
                identity.getNormalizedIdentifier(),
                user.getFirstName(),
                user.getLastName(),
                user.getDisplayName(),
                user.getStatus(),
                identity.getStatus(),
                identity.getVerifiedAt() != null,
                new ProfileDtos.InterfacePreferencesResponse(
                        interfacePreference.getLanguageTag(),
                        interfacePreference.getTimeZone(),
                        interfacePreference.getTheme(),
                        interfacePreference.getTextSize(),
                        interfacePreference.isHighContrast(),
                        interfacePreference.isReducedMotion()),
                new ProfileDtos.FinancialPreferencesResponse(
                        financialPreference.getPrimaryCurrency(),
                        financialPreference.getMonthStartDay(),
                        financialPreference.getLowBalanceThreshold(),
                        financialPreference.isHideHomeAmounts()),
                notifications.stream().map(notification -> new ProfileDtos.NotificationPreferenceResponse(
                        notification.getId(),
                        notification.getEventType(),
                        notification.getChannel(),
                        notification.isEnabled(),
                        notification.getMinimumPriority(),
                        notification.getQuietHoursStart(),
                        notification.getQuietHoursEnd())).toList(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    private void validateLanguageTag(String languageTag) {
        try {
            Locale locale = new Locale.Builder().setLanguageTag(languageTag.strip()).build();
            if (locale.getLanguage().isBlank()) {
                throw new IllformedLocaleException("Language is missing");
            }
        } catch (IllformedLocaleException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "languageTag is invalid");
        }
    }

    private void validateTimeZone(String timeZone) {
        try {
            ZoneId.of(timeZone.strip());
        } catch (DateTimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "timeZone is invalid");
        }
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

    private ApiException incompleteProfile() {
        return new ApiException(HttpStatus.CONFLICT, "Profile preferences are incomplete");
    }
}
