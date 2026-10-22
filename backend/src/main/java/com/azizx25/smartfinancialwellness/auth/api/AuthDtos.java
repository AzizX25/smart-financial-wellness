package com.azizx25.smartfinancialwellness.auth.api;

import java.time.Instant;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.auth.persistence.AuthenticationIdentity;
import com.azizx25.smartfinancialwellness.auth.validation.BcryptPassword;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @BcryptPassword String password,
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @NotBlank @Size(max = 160) String displayName) {

        @Override
        public String toString() {
            return "RegisterRequest[redacted]";
        }
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @BcryptPassword(minBytes = 1) String password) {

        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    public record EmailRequest(
            @NotBlank @Email @Size(max = 320) String email) {
    }

    public record TokenRequest(
            @NotBlank @Size(max = 512) String token) {

        @Override
        public String toString() {
            return "TokenRequest[token=redacted]";
        }
    }

    public record PasswordResetConfirmRequest(
            @NotBlank @Size(max = 512) String token,
            @BcryptPassword String newPassword) {

        @Override
        public String toString() {
            return "PasswordResetConfirmRequest[token=redacted,password=redacted]";
        }
    }

    public record MessageResponse(String message) {
    }

    public record AccessTokenResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            UserResponse user) {

        @Override
        public String toString() {
            return "AccessTokenResponse[accessToken=redacted]";
        }
    }

    public record UserResponse(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String displayName,
            AppUser.Status accountStatus,
            AuthenticationIdentity.Status identityStatus,
            boolean emailVerified,
            Instant createdAt) {

        public static UserResponse from(AuthenticationIdentity identity) {
            AppUser user = identity.getUser();
            return new UserResponse(
                    user.getId(),
                    identity.getNormalizedIdentifier(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getDisplayName(),
                    user.getStatus(),
                    identity.getStatus(),
                    identity.getVerifiedAt() != null,
                    user.getCreatedAt());
        }

        @Override
        public String toString() {
            return "UserResponse[personalData=redacted]";
        }
    }
}
