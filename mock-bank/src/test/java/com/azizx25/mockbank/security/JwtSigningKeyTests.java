package com.azizx25.mockbank.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSigningKeyTests {
    @Test
    void acceptsIndependentlyGeneratedKey() {
        byte[] bytes = new SecureRandom().generateSeed(64);
        var key = JwtSigningKey.decode(Base64.getEncoder().encodeToString(bytes));
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(java.util.Arrays.equals(key.getEncoded(), bytes)).isTrue();
    }

    @Test
    void rejectsMissingMalformedAndShortKeysWithoutEchoingTheirValues() {
        for (String value : new String[] {"", "not-valid-base64!", Base64.getEncoder().encodeToString(new byte[31])}) {
            assertThatThrownBy(() -> JwtSigningKey.decode(value))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("private Base64")
                    .hasNoCause();
        }
        assertThatThrownBy(() -> JwtSigningKey.decode(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPreviouslyPublishedExampleEvenWhenItHasEnoughBytes() {
        String retired = Base64.getEncoder().encodeToString(
                "0123456789abcdef".repeat(2).getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> JwtSigningKey.decode(retired))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("published examples are rejected")
                .hasMessageNotContaining(retired);
    }
}
