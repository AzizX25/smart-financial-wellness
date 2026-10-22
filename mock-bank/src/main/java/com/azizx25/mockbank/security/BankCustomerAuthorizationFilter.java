package com.azizx25.mockbank.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.azizx25.mockbank.customer.persistence.BankCustomerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

@Component
public class BankCustomerAuthorizationFilter extends OncePerRequestFilter {

    private static final Pattern CUSTOMER_PATH =
            Pattern.compile("^/api/v1/customers/([^/]+)(?:/.*)?$");

    private final BankCustomerRepository customerRepository;
    private final MockBankAuthenticationEntryPoint authenticationEntryPoint;
    private final MockBankAccessDeniedHandler accessDeniedHandler;

    public BankCustomerAuthorizationFilter(
            BankCustomerRepository customerRepository,
            MockBankAuthenticationEntryPoint authenticationEntryPoint,
            MockBankAccessDeniedHandler accessDeniedHandler) {
        this.customerRepository = customerRepository;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Matcher matcher = CUSTOMER_PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!(SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken authentication)) {
            authenticationEntryPoint.write(
                    response,
                    request,
                    HttpStatus.UNAUTHORIZED,
                    "Invalid or expired bank authentication");
            return;
        }

        if (!"CUSTOMER".equals(authentication.getToken().getClaimAsString("token_type"))) {
            accessDeniedHandler.handle(
                    request,
                    response,
                    new org.springframework.security.access.AccessDeniedException(
                            "Customer token required"));
            return;
        }

        String requestedCustomerId = UriUtils.decode(matcher.group(1), StandardCharsets.UTF_8);
        String authenticatedCustomerId = authentication.getToken().getSubject();
        if (!requestedCustomerId.equals(authenticatedCustomerId)) {
            authenticationEntryPoint.write(
                    response,
                    request,
                    HttpStatus.NOT_FOUND,
                    "Customer not found");
            return;
        }

        boolean activeCustomer = customerRepository.findByExternalCustomerId(authenticatedCustomerId)
                .filter(customer -> "ACTIVE".equals(customer.getStatus()))
                .isPresent();
        if (!activeCustomer) {
            authenticationEntryPoint.write(
                    response,
                    request,
                    HttpStatus.UNAUTHORIZED,
                    "Invalid or expired bank authentication");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
