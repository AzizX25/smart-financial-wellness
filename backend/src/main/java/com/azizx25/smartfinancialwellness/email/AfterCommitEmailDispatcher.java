package com.azizx25.smartfinancialwellness.email;

import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditLogEntry;
import com.azizx25.smartfinancialwellness.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AfterCommitEmailDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AfterCommitEmailDispatcher.class);

    private final EmailDeliveryService emailDeliveryService;
    private final AuditService auditService;

    public AfterCommitEmailDispatcher(
            EmailDeliveryService emailDeliveryService,
            AuditService auditService) {
        this.emailDeliveryService = emailDeliveryService;
        this.auditService = auditService;
    }

    public void scheduleVerification(
            UUID authenticationIdentityId,
            String recipient,
            String displayName,
            String rawToken) {
        schedule(
                () -> emailDeliveryService.sendVerificationEmail(recipient, displayName, rawToken),
                "AUTH_EMAIL_VERIFICATION_DELIVERY_FAILED",
                authenticationIdentityId);
    }

    public void schedulePasswordReset(
            UUID authenticationIdentityId,
            String recipient,
            String displayName,
            String rawToken) {
        schedule(
                () -> emailDeliveryService.sendPasswordResetEmail(recipient, displayName, rawToken),
                "AUTH_PASSWORD_RESET_DELIVERY_FAILED",
                authenticationIdentityId);
    }

    private void schedule(
            Runnable delivery,
            String failureAuditAction,
            UUID authenticationIdentityId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("After-commit email dispatch requires an active transaction");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    delivery.run();
                } catch (RuntimeException exception) {
                    LOGGER.warn("Account-action email delivery failed after transaction commit");
                    recordDeliveryFailure(failureAuditAction, authenticationIdentityId);
                }
            }
        });
    }

    private void recordDeliveryFailure(String action, UUID authenticationIdentityId) {
        try {
            auditService.recordSystemInNewTransaction(
                    action,
                    "AUTHENTICATION_IDENTITY",
                    authenticationIdentityId,
                    AuditLogEntry.Outcome.FAILED);
        } catch (RuntimeException exception) {
            LOGGER.warn("Account-action email delivery failure could not be audited");
        }
    }
}
