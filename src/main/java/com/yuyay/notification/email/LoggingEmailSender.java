package com.yuyay.notification.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!prod")
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(String to, String subject, String html) {
        log.info(
                "EMAIL SIMULADO -> destinatario: {}, asunto: {}, contenido: {}",
                to,
                subject,
                html
        );
    }
}