package com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import com.azizx25.smartfinancialwellness.bank.ReadOnlyBankDataPort;
import com.azizx25.smartfinancialwellness.bank.exception.InvalidBankRequestException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankNotFoundException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankProtocolException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankRejectedRequestException;
import com.azizx25.smartfinancialwellness.bank.exception.MockBankUnavailableException;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankAccount;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankBalance;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransaction;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransactionPage;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank.MockBankHttpDtos.AccountResponse;
import static com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank.MockBankHttpDtos.BalanceResponse;
import static com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank.MockBankHttpDtos.ErrorResponse;
import static com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank.MockBankHttpDtos.TransactionPageResponse;
import static com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank.MockBankHttpDtos.TransactionResponse;

final class MockBankHttpClient implements ReadOnlyBankDataPort {

    private static final String CUSTOMER_NOT_FOUND = "Customer not found";
    private static final String ACCOUNT_NOT_FOUND = "Account not found";
    private static final ParameterizedTypeReference<List<AccountResponse>> ACCOUNT_LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    MockBankHttpClient(RestClient restClient, JsonMapper jsonMapper) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<ExternalBankAccount> listAccounts(String customerId) {
        validateIdentifier(customerId, "customerId");
        List<AccountResponse> response = execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment("api", "v1", "customers", customerId, "accounts")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(ACCOUNT_LIST_TYPE));
        if (response == null) {
            throw new MockBankProtocolException();
        }
        return response.stream().map(this::mapAccount).toList();
    }

    @Override
    public ExternalBankAccount getAccount(String customerId, String accountId) {
        validateIdentifiers(customerId, accountId);
        AccountResponse response = execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment("api", "v1", "customers", customerId, "accounts", accountId)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(AccountResponse.class));
        return mapAccount(requireBody(response));
    }

    @Override
    public ExternalBankBalance getBalance(String customerId, String accountId) {
        validateIdentifiers(customerId, accountId);
        BalanceResponse response = execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(
                                "api", "v1", "customers", customerId,
                                "accounts", accountId, "balance")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(BalanceResponse.class));
        return mapBalance(requireBody(response));
    }

    @Override
    public ExternalBankTransactionPage listTransactions(
            String customerId,
            String accountId,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        validateIdentifiers(customerId, accountId);
        validateTransactionRequest(from, to, page, size);

        TransactionPageResponse response = execute(() -> restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.pathSegment(
                            "api", "v1", "customers", customerId,
                            "accounts", accountId, "transactions");
                    if (from != null) {
                        builder.queryParam("from", from);
                    }
                    if (to != null) {
                        builder.queryParam("to", to);
                    }
                    return builder
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build();
                })
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .body(TransactionPageResponse.class));
        return mapTransactionPage(requireBody(response));
    }

    private <T> T execute(Supplier<T> request) {
        try {
            return request.get();
        } catch (MockBankNotFoundException
                | MockBankRejectedRequestException
                | MockBankUnavailableException
                | MockBankProtocolException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new MockBankUnavailableException();
        } catch (RestClientException exception) {
            throw new MockBankProtocolException();
        }
    }

    private void handleError(
            org.springframework.http.HttpRequest request,
            ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.value() == 404) {
            throw new MockBankNotFoundException(readSafeNotFoundMessage(response));
        }
        if (status.is5xxServerError()) {
            throw new MockBankUnavailableException();
        }
        if (status.is4xxClientError()) {
            throw new MockBankRejectedRequestException();
        }
        throw new MockBankProtocolException();
    }

    private String readSafeNotFoundMessage(ClientHttpResponse response) {
        try {
            ErrorResponse error = jsonMapper.readValue(response.getBody(), ErrorResponse.class);
            if (error != null
                    && (CUSTOMER_NOT_FOUND.equals(error.message())
                    || ACCOUNT_NOT_FOUND.equals(error.message()))) {
                return error.message();
            }
        } catch (IOException | JacksonException exception) {
            // An invalid error payload is deliberately replaced with a fixed safe message.
        }
        return "Mock-bank resource not found";
    }

    private ExternalBankAccount mapAccount(AccountResponse response) {
        requireContractField(response);
        requireContractField(response.externalAccountId());
        requireContractField(response.displayName());
        requireContractField(response.accountType());
        requireContractField(response.currency());
        requireContractField(response.maskedAccountReference());
        requireContractField(response.status());
        requireContractField(response.createdAt());
        return new ExternalBankAccount(
                response.externalAccountId(),
                response.displayName(),
                response.accountType(),
                response.currency(),
                response.maskedAccountReference(),
                response.status(),
                response.createdAt());
    }

    private ExternalBankBalance mapBalance(BalanceResponse response) {
        requireContractField(response.externalAccountId());
        requireContractField(response.currency());
        requireContractField(response.currentBalance());
        requireContractField(response.availableBalance());
        requireContractField(response.status());
        requireContractField(response.maskedAccountReference());
        return new ExternalBankBalance(
                response.externalAccountId(),
                response.currency(),
                response.currentBalance(),
                response.availableBalance(),
                response.status(),
                response.maskedAccountReference());
    }

    private ExternalBankTransactionPage mapTransactionPage(TransactionPageResponse response) {
        requireContractField(response.items());
        requireContractField(response.page());
        requireContractField(response.size());
        requireContractField(response.totalElements());
        requireContractField(response.totalPages());
        requireContractField(response.first());
        requireContractField(response.last());
        if (response.page() < 0
                || response.size() < 1
                || response.totalElements() < 0
                || response.totalPages() < 0) {
            throw new MockBankProtocolException();
        }
        return new ExternalBankTransactionPage(
                response.items().stream().map(this::mapTransaction).toList(),
                response.page(),
                response.size(),
                response.totalElements(),
                response.totalPages(),
                response.first(),
                response.last());
    }

    private ExternalBankTransaction mapTransaction(TransactionResponse response) {
        requireContractField(response);
        requireContractField(response.externalTransactionId());
        requireContractField(response.bookingDate());
        requireContractField(response.amount());
        requireContractField(response.direction());
        requireContractField(response.currency());
        requireContractField(response.description());
        requireContractField(response.status());
        return new ExternalBankTransaction(
                response.externalTransactionId(),
                response.bookingDate(),
                response.valueDate(),
                response.amount(),
                response.direction(),
                response.currency(),
                response.description(),
                response.counterpartyName(),
                response.status());
    }

    private static <T> T requireBody(T body) {
        requireContractField(body);
        return body;
    }

    private static void requireContractField(Object value) {
        if (value == null) {
            throw new MockBankProtocolException();
        }
    }

    private static void validateIdentifiers(String customerId, String accountId) {
        validateIdentifier(customerId, "customerId");
        validateIdentifier(accountId, "accountId");
    }

    private static void validateIdentifier(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidBankRequestException(name + " must not be blank");
        }
    }

    private static void validateTransactionRequest(
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        if (page < 0) {
            throw new InvalidBankRequestException("page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new InvalidBankRequestException("size must be between 1 and 100");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidBankRequestException("from must not be after to");
        }
    }
}
