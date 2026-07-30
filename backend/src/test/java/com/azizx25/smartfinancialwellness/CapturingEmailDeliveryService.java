package com.azizx25.smartfinancialwellness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.azizx25.smartfinancialwellness.email.EmailDeliveryException;
import com.azizx25.smartfinancialwellness.email.EmailDeliveryService;

class CapturingEmailDeliveryService implements EmailDeliveryService {

    enum Type {
        VERIFICATION,
        PASSWORD_RESET
    }

    private final List<CapturedEmail> messages = new CopyOnWriteArrayList<>();
    private volatile boolean deliveryFailure;

    @Override
    public void sendVerificationEmail(String recipient, String displayName, String rawToken) {
        failWhenConfigured();
        messages.add(new CapturedEmail(Type.VERIFICATION, recipient, displayName, rawToken));
    }

    @Override
    public void sendPasswordResetEmail(String recipient, String displayName, String rawToken) {
        failWhenConfigured();
        messages.add(new CapturedEmail(Type.PASSWORD_RESET, recipient, displayName, rawToken));
    }

    void clear() {
        messages.clear();
        deliveryFailure = false;
    }

    void failDeliveries() {
        deliveryFailure = true;
    }

    void allowDeliveries() {
        deliveryFailure = false;
    }

    List<CapturedEmail> messagesOfType(Type type) {
        return messages.stream()
                .filter(message -> message.type() == type)
                .toList();
    }

    CapturedEmail latest(Type type, String recipient) {
        List<CapturedEmail> matching = messages.stream()
                .filter(message -> message.type() == type && message.recipient().equals(recipient))
                .toList();
        if (matching.isEmpty()) {
            throw new AssertionError("No captured email matched the requested type and recipient");
        }
        return matching.getLast();
    }

    private void failWhenConfigured() {
        if (deliveryFailure) {
            throw new EmailDeliveryException();
        }
    }

    record CapturedEmail(Type type, String recipient, String displayName, String rawToken) {

        @Override
        public String toString() {
            return "CapturedEmail[type=" + type + ",recipient=redacted,rawToken=redacted]";
        }
    }
}
