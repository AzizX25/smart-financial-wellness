package com.azizx25.mockbank.client;

import com.azizx25.mockbank.client.persistence.OAuthClient;
import com.azizx25.mockbank.client.persistence.OAuthClientRepository;
import com.azizx25.mockbank.exception.ClientInactiveException;
import com.azizx25.mockbank.exception.ClientNotFoundException;
import com.azizx25.mockbank.exception.InvalidRedirectUriException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OAuthClientLookupService {

    private final OAuthClientRepository clientRepository;

    public OAuthClientLookupService(OAuthClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    public OAuthClientSummary loadActiveClient(String clientIdentifier, String redirectUri) {
        OAuthClient client = clientRepository.findByClientIdentifier(clientIdentifier)
                .orElseThrow(ClientNotFoundException::new);
        if (!client.isEnabled()) {
            throw new ClientInactiveException();
        }
        if (!client.getRedirectUri().equals(redirectUri)) {
            throw new InvalidRedirectUriException();
        }
        return new OAuthClientSummary(
                client.getClientIdentifier(),
                client.getClientName(),
                client.getRedirectUri(),
                client.getCreatedAt());
    }
}