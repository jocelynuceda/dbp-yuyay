package com.yuyay.notification.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.yuyay.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnExpression("!'${app.mail.resend-api-key:}'.isEmpty()")
public class ResendEmailSender implements EmailSender {

    private final Resend resend;
    private final String from;

    @Autowired
    public ResendEmailSender(AppProperties appProperties) {
        this(new Resend(appProperties.mail().resendApiKey()), appProperties.mail().from());
    }

    ResendEmailSender(Resend resend, String from) {
        this.resend = resend;
        this.from = from;
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
            // Quien llama decide: los avisos solo lo registran y los recordatorios lo reintentan
            throw new EmailDeliveryException("Resend rechazó el correo", e);
        }
    }
}