package com.azizx25.smartfinancialwellness.security;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.auth.persistence.AuthenticationIdentity;
import com.azizx25.smartfinancialwellness.auth.persistence.AuthenticationIdentityRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class StatusAwareAuthorizationFilter extends OncePerRequestFilter {

    private static final Set<String> PENDING_GET_PATHS = Set.of("/api/auth/me", "/api/profile");
    private static final Set<String> PENDING_POST_PATHS = Set.of("/api/auth/refresh", "/api/auth/logout");

    private final AuthenticationIdentityRepository identityRepository;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public StatusAwareAuthorizationFilter(
            AuthenticationIdentityRepository identityRepository,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) {
        this.identityRepository = identityRepository;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            filterChain.doFilter(request, response);
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(jwtAuthentication.getToken().getSubject());
        } catch (IllegalArgumentException exception) {
            rejectAuthentication(request, response);
            return;
        }

        AuthenticationIdentity identity = identityRepository.findByUserId(userId).orElse(null);
        if (identity == null || isBlocked(identity)) {
            rejectAuthentication(request, response);
            return;
        }

        if (!isFullyActive(identity) && !isAllowedForPendingAccount(request)) {
            accessDeniedHandler.handle(
                    request,
                    response,
                    new AccessDeniedException("Account verification is required"));
            return;
        }

        CurrentUserPrincipal principal = new CurrentUserPrincipal(
                userId,
                identity.getId(),
                identity.getUser().getStatus(),
                identity.getStatus());
        UsernamePasswordAuthenticationToken statusAwareAuthentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        jwtAuthentication.getAuthorities());
        statusAwareAuthentication.setDetails(jwtAuthentication.getDetails());
        SecurityContextHolder.getContext().setAuthentication(statusAwareAuthentication);
        filterChain.doFilter(request, response);
    }

    private boolean isBlocked(AuthenticationIdentity identity) {
        AppUser.Status userStatus = identity.getUser().getStatus();
        return identity.getStatus() == AuthenticationIdentity.Status.DISABLED
                || userStatus == AppUser.Status.SUSPENDED
                || userStatus == AppUser.Status.DELETION_PENDING
                || userStatus == AppUser.Status.DELETED;
    }

    private boolean isFullyActive(AuthenticationIdentity identity) {
        return identity.getUser().getStatus() == AppUser.Status.ACTIVE
                && identity.getStatus() == AuthenticationIdentity.Status.ACTIVE;
    }

    private boolean isAllowedForPendingAccount(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        return (HttpMethod.GET.matches(method) && PENDING_GET_PATHS.contains(path))
                || (HttpMethod.PUT.matches(method) && "/api/profile".equals(path))
                || (HttpMethod.POST.matches(method) && PENDING_POST_PATHS.contains(path));
    }

    private void rejectAuthentication(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.commence(
                request,
                response,
                new InsufficientAuthenticationException("Account is unavailable"));
    }
}
