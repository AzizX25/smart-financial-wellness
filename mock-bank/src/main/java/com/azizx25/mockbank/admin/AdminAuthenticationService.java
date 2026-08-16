package com.azizx25.mockbank.admin;

import java.util.Locale;

import com.azizx25.mockbank.admin.api.AdminDtos.LoginResponse;
import com.azizx25.mockbank.admin.persistence.BankAdministrator;
import com.azizx25.mockbank.admin.persistence.BankAdministratorRepository;
import com.azizx25.mockbank.exception.InvalidBankCredentialsException;
import com.azizx25.mockbank.security.MockBankJwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminAuthenticationService {

    private static final String DUMMY_BCRYPT_HASH =
            "$2a$12$iH0ORIlDjTg4Knk2AIIpneDiWATEjHj8RcHEhF.PHxJs4aoIJ8VT.";

    private final BankAdministratorRepository administratorRepository;
    private final PasswordEncoder passwordEncoder;
    private final MockBankJwtService jwtService;

    public AdminAuthenticationService(
            BankAdministratorRepository administratorRepository,
            PasswordEncoder passwordEncoder,
            MockBankJwtService jwtService) {
        this.administratorRepository = administratorRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String username, String password) {
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        BankAdministrator administrator = administratorRepository.findByUsername(normalized).orElse(null);
        String hash = administrator == null ? DUMMY_BCRYPT_HASH : administrator.getPasswordHash();
        boolean matches = passwordEncoder.matches(password, hash);
        if (administrator == null || !matches || !"ACTIVE".equals(administrator.getStatus())) {
            throw new InvalidBankCredentialsException();
        }
        var token = jwtService.issueAdmin(administrator.getUsername());
        return new LoginResponse(token.value(), "Bearer", token.expiresAt(), administrator.getUsername());
    }
}
