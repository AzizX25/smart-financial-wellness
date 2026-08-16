package com.azizx25.mockbank.auth;

import java.util.Locale;

import com.azizx25.mockbank.auth.api.MockBankAuthDtos.LoginResponse;
import com.azizx25.mockbank.customer.persistence.BankCustomer;
import com.azizx25.mockbank.customer.persistence.BankCustomerRepository;
import com.azizx25.mockbank.exception.InvalidBankCredentialsException;
import com.azizx25.mockbank.security.MockBankJwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MockBankAuthenticationService {

    private static final String DUMMY_BCRYPT_HASH =
            "$2a$12$iH0ORIlDjTg4Knk2AIIpneDiWATEjHj8RcHEhF.PHxJs4aoIJ8VT.";

    private final BankCustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final MockBankJwtService jwtService;

    public MockBankAuthenticationService(
            BankCustomerRepository customerRepository,
            PasswordEncoder passwordEncoder,
            MockBankJwtService jwtService) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String username, String password) {
        String normalizedUsername = username.strip().toLowerCase(Locale.ROOT);
        BankCustomer customer = customerRepository.findByUsername(normalizedUsername).orElse(null);
        String hash = customer == null ? DUMMY_BCRYPT_HASH : customer.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(password, hash);
        if (customer == null || !passwordMatches || !"ACTIVE".equals(customer.getStatus())) {
            throw new InvalidBankCredentialsException();
        }

        MockBankJwtService.IssuedBankToken token = jwtService.issue(customer.getExternalCustomerId());
        return new LoginResponse(
                token.value(),
                "Bearer",
                token.expiresAt(),
                customer.getExternalCustomerId());
    }
}
