package com.azizx25.smartfinancialwellness.bank.persistence;

import java.time.Instant;

import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BankConnectionTests {

    @Test
    void lifecycleMutationsUseTheSuppliedDeterministicTimestamp() {
        Instant createdAt = Instant.parse("2026-01-01T08:00:00Z");
        Instant activatedAt = Instant.parse("2026-01-01T09:00:00Z");
        Instant erroredAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant disconnectedAt = Instant.parse("2026-01-01T11:00:00Z");
        BankConnection connection = new BankConnection(
                mock(AppUser.class),
                "MOCK-CUST-001",
                createdAt);

        assertThat(connection.getCreatedAt()).isEqualTo(createdAt);
        assertThat(connection.getUpdatedAt()).isEqualTo(createdAt);

        connection.activate("MOCK-CUST-002", activatedAt);
        assertThat(connection.getUpdatedAt()).isEqualTo(activatedAt);

        connection.markError(erroredAt);
        assertThat(connection.getUpdatedAt()).isEqualTo(erroredAt);

        connection.disconnect(disconnectedAt);
        assertThat(connection.getUpdatedAt()).isEqualTo(disconnectedAt);
        assertThat(connection.getDisconnectedAt()).isEqualTo(disconnectedAt);
        assertThat(connection.getStatus()).isEqualTo(BankConnection.Status.DISCONNECTED);
        assertThat(connection.getExternalCustomerId()).isNull();
    }
}
