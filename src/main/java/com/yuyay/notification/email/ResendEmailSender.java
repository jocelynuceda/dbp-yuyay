package com.yuyay.notification.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.yuyay.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnExpression("!'${app.mail.resend-api-key:}'.isEmpty()")
public class ResendEmailSender implements EmailSender {

    private final Resend resend;
    private final String from;

    public ResendEmailSender(AppProperties appProperties) {
        this.resend = new Resend(appProperties.mail().resendApiKey());
        this.from = appProperties.mail().from();
    }

    @Override
    public void send(String to, String subject, String html) {
        CreateEmailOptions email = CreateEmailOptions.builder()
                .from(from)
                .to(to)
                .subject(subject)
                .html(html)
                .build();

        try {
            resend.emails().send(email);
            log.info("Correo enviado a {}: \"{}\"", to, subject);
        } catch (ResendException e) {
            log.error(
                    "No se pudo enviar correo a {}: {}",
                    to,
                    e.getMessage(),
                    e
            );
        }
    }
}