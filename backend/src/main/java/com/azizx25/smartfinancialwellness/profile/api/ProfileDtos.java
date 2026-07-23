package com.azizx25.smartfinancialwellness.profile.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.auth.persistence.AuthenticationIdentity;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.InterfacePreference;
import com.azizx25.smartfinancialwellness.user.persistence.NotificationPreference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ProfileDtos {

    private ProfileDtos() {
    }

    public record UpdateProfileRequest(
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @NotBlank @Size(max = 160) String displayName,
            @NotNull @Valid InterfacePreferencesRequest interfacePreferences,
            @NotNull @Valid FinancialPreferencesRequest financialPreferences) {

        @Override
        public String toString() {
            return "UpdateProfileRequest[personalData=redacted]";
        }
    }

    public record InterfacePreferencesRequest(
            @NotBlank @Size(max = 35) String languageTag,
            @NotBlank @Size(max = 80) String timeZone,
            @NotNull InterfacePreference.Theme theme,
            @NotNull InterfacePreference.TextSize textSize,
            boolean highContrast,
            boolean reducedMotion) {
    }

    public record FinancialPreferencesRequest(
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String primaryCurrency,
            @Min(1) @Max(28) short monthStartDay,
            @DecimalMin("0.0000") BigDecimal lowBalanceThreshold,
            boolean hideHomeAmounts) {
    }

    public record ProfileResponse(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String displayName,
            AppUser.Status accountStatus,
            AuthenticationIdentity.Status identityStatus,
            boolean emailVerified,
            InterfacePreferencesResponse interfacePreferences,
            FinancialPreferencesResponse financialPreferences,
            List<NotificationPreferenceResponse> notificationPreferences,
            Instant createdAt,
            Instant updatedAt) {

        @Override
        public String toString() {
            return "ProfileResponse[personalData=redacted]";
        }
    }

    public record InterfacePreferencesResponse(
            String languageTag,
            String timeZone,
            InterfacePreference.Theme theme,
            InterfacePreference.TextSize textSize,
            boolean highContrast,
            boolean reducedMotion) {
    }

    public record FinancialPreferencesResponse(
            String primaryCurrency,
            short monthStartDay,
            BigDecimal lowBalanceThreshold,
            boolean hideHomeAmounts) {
    }

    public record NotificationPreferenceResponse(
            UUID id,
            String eventType,
            NotificationPreference.Channel channel,
            boolean enabled,
            NotificationPreference.Priority minimumPriority,
            LocalTime quietHoursStart,
            LocalTime quietHoursEnd) {
    }
}
