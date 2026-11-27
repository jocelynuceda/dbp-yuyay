package com.yuyay.notification.email;

import com.yuyay.config.AppProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Envía por SMTP (por ejemplo Gmail). Tiene prioridad sobre Resend porque
 * entrega a cualquier destinatario sin necesitar un dominio propio, mientras
 * que el remitente de pruebas de Resend solo llega al dueño de la cuenta.
 */
@Slf4j
@Component
@ConditionalOnExpression("!'${spring.mail.username:}'.isEmpty()")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(
            JavaMailSender mailSender,
            AppProperties appProperties,
            @Value("${spring.mail.username:}") String username
    ) {
        this.mailSender = mailSender;
        this.from = resolveFrom(appProperties.mail().from(), username);
    }

    /**
     * Gmail reescribe el remitente si no coincide con la cuenta autenticada,
     * así que solo respetamos MAIL_FROM cuando apunta a esa misma dirección.
     */
    private static String resolveFrom(String configured, String username) {
        if (configured != null
                && configured.toLowerCase().contains(username.toLowerCase())) {
            return configured;
        }
        return "Yuyay <" + username + ">";
    }

    @Override
    public void send(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message,
                    false,
                    StandardCharsets.UTF_8.name()
            );

            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("Correo enviado a {}: \"{}\"", to, subject);
        } catch (MailException | jakarta.mail.MessagingException e) {
            log.error(
                    "No se pudo enviar correo a {}: {}",
                    to,
                    e.getMessage(),
                    e
            );
        }
    }
}
