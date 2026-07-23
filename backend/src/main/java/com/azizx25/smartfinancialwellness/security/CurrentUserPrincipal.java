package com.azizx25.smartfinancialwellness.security;

import java.security.Principal;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.auth.persistence.AuthenticationIdentity;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;

public record CurrentUserPrincipal(
        UUID userId,
        UUID identityId,
        AppUser.Status userStatus,
        AuthenticationIdentity.Status identityStatus) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
