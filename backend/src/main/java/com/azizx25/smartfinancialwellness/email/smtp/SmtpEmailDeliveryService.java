package com.azizx25.smartfinancialwellness.email.smtp;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import com.azizx25.smartfinancialwellness.config.EmailProperties;
import com.azizx25.smartfinancialwellness.email.EmailDeliveryException;
import com.azizx25.smartfinancialwellness.email.EmailDeliveryService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class SmtpEmailDeliveryService implements EmailDeliveryService {

    private final JavaMailSender mailSender;
    private final EmailProperties properties;

    public SmtpEmailDeliveryService(JavaMailSender mailSender, EmailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendVerificationEmail(String recipient, String displayName, String rawToken) {
        send(
                recipient,
                "Vérifiez votre adresse e-mail",
                """
                Bonjour %s,

                Votre compte Mon Équilibre a bien été créé. Pour l’activer, ouvrez ce lien :

                %s

                Si vous n’êtes pas à l’origine de cette demande, vous pouvez ignorer cet e-mail.
                """.formatted(displayName, actionLink("/verify-email", rawToken)));
    }

    @Override
    public void sendPasswordResetEmail(String recipient, String displayName, String rawToken) {
        send(
                recipient,
                "Réinitialisez votre mot de passe",
                """
                Bonjour %s,

                Une réinitialisation du mot de passe de votre compte Mon Équilibre a été demandée.
                Pour choisir un nouveau mot de passe, ouvrez ce lien :

                %s

                Si vous n’êtes pas à l’origine de cette demande, vous pouvez ignorer cet e-mail.
                """.formatted(displayName, actionLink("/reset-password", rawToken)));
    }

    private void send(String recipient, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.senderAddress(), properties.senderName());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException exception) {
            throw new EmailDeliveryException();
        }
    }

    private String actionLink(String path, String rawToken) {
        String baseUrl = properties.frontendBaseUrl().toString();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path + "#token=" + rawToken;
    }
}
