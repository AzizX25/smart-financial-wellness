package com.azizx25.smartfinancialwellness.bank;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.MockBankAuthenticationPort.BankLoginResult;
import com.azizx25.smartfinancialwellness.bank.api.BankDtos.BankConnectionResponse;
import com.azizx25.smartfinancialwellness.bank.exception.BankConnectionConflictException;
import com.azizx25.smartfinancialwellness.bank.exception.BankConnectionRequiredException;
import com.azizx25.smartfinancialwellness.bank.exception.BankReconnectionRequiredException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankProtocolException;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnection;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnectionAuthorization;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnectionAuthorizationRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.BankConnectionRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccount;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccountRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankConnectionService {

    private static final Set<BankConnection.Status> CURRENT_STATUSES =
            EnumSet.of(
                    BankConnection.Status.PENDING,
                    BankConnection.Status.ACTIVE,
                    BankConnection.Status.ERROR);

    private final MockBankAuthenticationPort authenticationPort;
    private final AppUserRepository userRepository;
    private final BankConnectionRepository connectionRepository;
    private final BankConnectionAuthorizationRepository authorizationRepository;
    private final FinancialAccountRepository accountRepository;
    private final AuditService auditService;
    private final Clock clock;

    public BankConnectionService(
            MockBankAuthenticationPort authenticationPort,
            AppUserRepository userRepository,
            BankConnectionRepository connectionRepository,
            BankConnectionAuthorizationRepository authorizationRepository,
            FinancialAccountRepository accountRepository,
            AuditService auditService,
            Clock clock) {
        this.authenticationPort = authenticationPort;
        this.userRepository = userRepository;
        this.connectionRepository = connectionRepository;
        this.authorizationRepository = authorizationRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public BankConnectionResponse connect(UUID userId, String username, String password) {
        BankLoginResult login = authenticationPort.login(username, password);
        Instant now = clock.instant();
        if (!login.expiresAt().isAfter(now)) {
            throw new MockBankProtocolException();
        }

        AppUser user = userRepository.findById(userId)
                .orElseThrow(BankConnectionRequiredException::new);
        Optional<BankConnection> current = findCurrent(userId);
        Optional<BankConnection> reusable = current.or(() -> connectionRepository
                .findTopByUser_IdAndProviderCodeOrderByCreatedAtDesc(
                        userId,
                        BankConnection.MOCK_BANK_PROVIDER)
                .filter(connection -> connection.getStatus() == BankConnection.Status.DISCONNECTED));
        Optional<BankConnection> customerLink = connectionRepository
                .findByProviderCodeAndExternalCustomerIdAndStatusIn(
                        BankConnection.MOCK_BANK_PROVIDER,
                        login.customerId(),
                        CURRENT_STATUSES);
        if (customerLink.isPresent() && !customerLink.orElseThrow().getUserId().equals(userId)) {
            throw new BankConnectionConflictException(
                    "This mock-bank customer is already linked to another user");
        }
        if (current.isPresent()
                && !login.customerId().equals(current.orElseThrow().getExternalCustomerId())) {
            throw new BankConnectionConflictException(
                    "Disconnect the existing mock-bank connection before linking another customer");
        }

        BankConnection connection = reusable.orElseGet(() ->
                new BankConnection(user, login.customerId(), now));
        connection.activate(login.customerId(), now);

        if (reusable.isPresent()) {
            authorizationRepository.findByBankConnection_IdAndStatus(
                            connection.getId(),
                            BankConnectionAuthorization.Status.ACTIVE)
                    .ifPresent(authorization -> authorization.revoke(now, "REAUTHENTICATED"));
            authorizationRepository.flush();
        }

        try {
            connectionRepository.saveAndFlush(connection);
            BankConnectionAuthorization authorization = authorizationRepository.saveAndFlush(
                    new BankConnectionAuthorization(
                            connection,
                            login.accessToken(),
                            login.expiresAt(),
                            now));
            auditService.record(
                    userId,
                    reusable.isPresent() ? "MOCK_BANK_RECONNECTED" : "MOCK_BANK_CONNECTED",
                    "BANK_CONNECTION",
                    connection.getId());
            return toResponse(connection, authorization, now);
        } catch (DataIntegrityViolationException exception) {
            throw new BankConnectionConflictException(
                    "The mock-bank connection conflicts with an existing link");
        }
    }

    @Transactional(readOnly = true)
    public BankConnectionResponse getStatus(UUID userId) {
        BankConnection connection = connectionRepository
                .findTopByUser_IdAndProviderCodeOrderByCreatedAtDesc(
                        userId,
                        BankConnection.MOCK_BANK_PROVIDER)
                .orElseThrow(BankConnectionRequiredException::new);
        BankConnectionAuthorization authorization = authorizationRepository
                .findTopByBankConnection_IdOrderByConsentedAtDesc(connection.getId())
                .orElse(null);
        return toResponse(connection, authorization, clock.instant());
    }

    @Transactional
    public void disconnect(UUID userId) {
        BankConnection connection = findCurrent(userId).orElse(null);
        if (connection == null) {
            return;
        }

        Instant now = clock.instant();
        authorizationRepository.findByBankConnection_IdAndStatus(
                        connection.getId(),
                        BankConnectionAuthorization.Status.ACTIVE)
                .ifPresent(authorization -> authorization.revoke(now, "USER_DISCONNECTED"));
        accountRepository.findByBankConnection_IdAndUser_Id(connection.getId(), userId)
                .forEach(FinancialAccount::disconnect);
        connection.disconnect(now);
        auditService.record(
                userId,
                "MOCK_BANK_DISCONNECTED",
                "BANK_CONNECTION",
                connection.getId());
    }

    @Transactional(readOnly = true)
    public AuthorizedBankConnection requireAccess(UUID userId, String requiredScope) {
        BankConnection connection = findCurrent(userId)
                .orElseThrow(BankConnectionRequiredException::new);
        if (connection.getStatus() != BankConnection.Status.ACTIVE) {
            throw new BankReconnectionRequiredException();
        }
        BankConnectionAuthorization authorization = authorizationRepository
                .findByBankConnection_IdAndStatus(
                        connection.getId(),
                        BankConnectionAuthorization.Status.ACTIVE)
                .orElseThrow(BankReconnectionRequiredException::new);
        if (!authorization.isUsableAt(clock.instant()) || !authorization.grants(requiredScope)) {
            throw new BankReconnectionRequiredException();
        }
        return new AuthorizedBankConnection(
                connection.getId(),
                authorization.getId(),
                new BankAccessCredentials(
                        connection.getExternalCustomerId(),
                        authorization.getProviderAccessToken()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidateProviderAuthentication(UUID userId, UUID connectionId) {
        BankConnection connection = connectionRepository.findByIdAndUser_Id(connectionId, userId)
                .orElse(null);
        if (connection == null) {
            return;
        }
        authorizationRepository.findByBankConnection_IdAndStatus(
                        connectionId,
                        BankConnectionAuthorization.Status.ACTIVE)
                .ifPresent(BankConnectionAuthorization::expire);
        connection.markError(clock.instant());
        auditService.record(
                userId,
                "MOCK_BANK_AUTHENTICATION_INVALIDATED",
                "BANK_CONNECTION",
                connectionId);
    }

    private Optional<BankConnection> findCurrent(UUID userId) {
        return connectionRepository.findByUser_IdAndProviderCodeAndStatusIn(
                userId,
                BankConnection.MOCK_BANK_PROVIDER,
                CURRENT_STATUSES);
    }

    private BankConnectionResponse toResponse(
            BankConnection connection,
            BankConnectionAuthorization authorization,
            Instant now) {
        boolean reconnectRequired = connection.getStatus() != BankConnection.Status.ACTIVE
                || authorization == null
                || !authorization.isUsableAt(now);
        return new BankConnectionResponse(
                connection.getId(),
                connection.getProviderCode(),
                connection.getStatus().name(),
                reconnectRequired,
                authorization == null ? null : authorization.getConsentedAt(),
                authorization == null ? null : authorization.getExpiresAt(),
                connection.getDisconnectedAt());
    }

    public record AuthorizedBankConnection(
            UUID connectionId,
            UUID authorizationId,
            BankAccessCredentials credentials) {

        @Override
        public String toString() {
            return "AuthorizedBankConnection[connectionId=" + connectionId
                    + ", credentials=<redacted>]";
        }
    }
}
