package com.azizx25.mockbank.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azizx25.mockbank.config.MockBankSecurityProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class MockBankJwtService {

    private final JwtEncoder jwtEncoder;
    private final MockBankSecurityProperties properties;
    private final Clock clock;

    public MockBankJwtService(
            JwtEncoder jwtEncoder,
            MockBankSecurityProperties properties,
            Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedBankToken issue(String externalCustomerId) {
        return issue(externalCustomerId, "CUSTOMER", properties.jwt().accessTokenTtl());
    }

    public IssuedBankToken issueAdmin(String username) {
        return issue(username, "ADMIN", properties.jwt().adminAccessTokenTtl());
    }

    private IssuedBankToken issue(
            String subject,
            String role,
            java.time.Duration timeToLive) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(timeToLive);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .audience(List.of(properties.jwt().audience()))
                .subject(subject)
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("role", role)
                .claim("token_type", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedBankToken(token, expiresAt);
    }

    public record IssuedBankToken(String value, Instant expiresAt) {

        @Override
        public String toString() {
            return "IssuedBankToken[value=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }
}
