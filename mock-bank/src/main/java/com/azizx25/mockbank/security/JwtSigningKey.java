package com.azizx25.mockbank.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Kept local to this independent service; no shared runtime dependency. */
public final class JwtSigningKey {
    private static final Set<String> RETIRED_FINGERPRINTS = Set.of(
            "3eb1bd439947eb762998e566ccc2e099c791118b2f40579cc4f7da2b5061b7f9",
            "7e6f2ad63131a4086bf11006bb6325909ca49389ec02a91dcb1c2c83a91f9620",
            "e49adb9264e0e7a1bb1fe28b8e50000409e127479d0c77a30c03b742bf977859",
            "f1cfb687623cee1b10e9c0809393745f3e4198838c3648446438af085f660899");

    private JwtSigningKey() {
    }

    public static SecretKey decode(String encoded) {
        String error = "JWT signing key must be private Base64 with at least 32 random bytes; published examples are rejected";
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(error);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(error);
        }
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded));
            if (decoded.length < 32 || RETIRED_FINGERPRINTS.contains(fingerprint)) {
                throw new IllegalStateException(error);
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for signing-key validation");
        }
        return new SecretKeySpec(decoded, "HmacSHA256");
    }
}
