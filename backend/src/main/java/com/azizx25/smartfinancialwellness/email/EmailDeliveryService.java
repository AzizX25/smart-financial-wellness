package com.azizx25.smartfinancialwellness.email;

public interface EmailDeliveryService {

    void sendVerificationEmail(String recipient, String displayName, String rawToken);

    void sendPasswordResetEmail(String recipient, String displayName, String rawToken);
}
