package com.azizx25.smartfinancialwellness.bank;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.MockBankAuthenticationPort.BankLoginResult;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnection;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnectionAuthorization;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnectionAuthorizationRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnectionRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccountRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BankConnectionServiceClockTests {

    @Test
    void servicePassesItsInjectedClockToEveryConnectionMutation() {
        UUID userId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T08:00:00Z");
        Instant activatedAt = Instant.parse("2026-01-01T09:00:00Z");
        Instant erroredAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant disconnectedAt = Instant.parse("2026-01-01T11:00:00Z");
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(userId);
        BankConnection connection = new BankConnection(user, "MOCK-CUST-001", createdAt);

        MockBankAuthenticationPort authenticationPort = mock(MockBankAuthenticationPort.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        BankConnectionRepository connectionRepository = mock(BankConnectionRepository.class);
        BankConnectionAuthorizationRepository authorizationRepository =
                mock(BankConnectionAuthorizationRepository.class);
        FinancialAccountRepository accountRepository = mock(FinancialAccountRepository.class);
        AuditService auditService = mock(AuditService.class);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(activatedAt, erroredAt, disconnectedAt);
        when(authenticationPort.login("customer", "password")).thenReturn(
                new BankLoginResult("MOCK-CUST-001", "provider-token", activatedAt.plusSeconds(600)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(connectionRepository.findByUser_IdAndProviderCodeAndStatusIn(any(), any(), any()))
                .thenReturn(Optional.of(connection));
        when(connectionRepository.findByProviderCodeAndExternalCustomerIdAndStatusIn(any(), any(), any()))
                .thenReturn(Optional.of(connection));
        when(connectionRepository.findByIdAndUser_Id(connection.getId(), userId))
                .thenReturn(Optional.of(connection));
        when(connectionRepository.saveAndFlush(connection)).thenReturn(connection);
        when(authorizationRepository.findByBankConnection_IdAndStatus(any(), any()))
                .thenReturn(Optional.empty());
        when(authorizationRepository.saveAndFlush(any(BankConnectionAuthorization.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.findByBankConnection_IdAndUser_Id(connection.getId(), userId))
                .thenReturn(List.of());

        BankConnectionService service = new BankConnectionService(
                authenticationPort,
                userRepository,
                connectionRepository,
                authorizationRepository,
                accountRepository,
                auditService,
                clock);

        service.connect(userId, "customer", "password");
        assertThat(connection.getUpdatedAt()).isEqualTo(activatedAt);

        service.invalidateProviderAuthentication(userId, connection.getId());
        assertThat(connection.getUpdatedAt()).isEqualTo(erroredAt);

        service.disconnect(userId);
        assertThat(connection.getUpdatedAt()).isEqualTo(disconnectedAt);
        assertThat(connection.getDisconnectedAt()).isEqualTo(disconnectedAt);
    }
}
