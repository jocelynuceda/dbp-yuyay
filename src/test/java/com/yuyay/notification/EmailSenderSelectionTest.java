package com.yuyay.notification;

import com.yuyay.config.AppProperties;
import com.yuyay.notification.email.EmailSender;
import com.yuyay.notification.email.LoggingEmailSender;
import com.yuyay.notification.email.ResendEmailSender;
import com.yuyay.notification.email.SmtpEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EmailSenderSelectionTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Senders.class);

    @Test
    void usesResendWhenThereIsAnApiKey() {
        runner.withPropertyValues(
                        "app.mail.resend-api-key=re_clave_de_prueba",
                        "app.mail.from=Yuyay <no-reply@yuyay.app>")
                .run(context -> assertThat(context)
                        .hasSingleBean(EmailSender.class)
                        .hasSingleBean(ResendEmailSender.class)
                        .doesNotHaveBean(LoggingEmailSender.class));
    }

    @Test
    void smtpWinsOverResendWhenThereAreCredentials() {
        // La prioridad al revés fue un fallo real: con la clave de Resend puesta, todo volvía a
        // salir por Resend, que con su remitente de pruebas solo entrega al dueño de la cuenta.
        runner.withPropertyValues(
                        "app.mail.resend-api-key=re_clave_de_prueba",
                        "spring.mail.username=yuyay.pruebas@gmail.com",
                        "app.mail.from=Yuyay <no-reply@yuyay.app>")
                .run(context -> assertThat(context)
                        .hasSingleBean(EmailSender.class)
                        .hasSingleBean(SmtpEmailSender.class)
                        .doesNotHaveBean(ResendEmailSender.class)
                        .doesNotHaveBean(LoggingEmailSender.class));
    }

    @Test
    void usesSmtpWhenItIsTheOnlyThingConfigured() {
        runner.withPropertyValues(
                        "spring.mail.username=yuyay.pruebas@gmail.com",
                        "app.mail.from=Yuyay <no-reply@yuyay.app>")
                .run(context -> assertThat(context)
                        .hasSingleBean(EmailSender.class)
                        .hasSingleBean(SmtpEmailSender.class)
                        .doesNotHaveBean(LoggingEmailSender.class));
    }

    @Test
    void anEmptySmtpUserCountsAsNoSmtp() {
        runner.withPropertyValues(
                        "spring.mail.username=",
                        "app.mail.resend-api-key=re_clave_de_prueba",
                        "app.mail.from=Yuyay <no-reply@yuyay.app>")
                .run(context -> assertThat(context)
                        .hasSingleBean(ResendEmailSender.class)
                        .doesNotHaveBean(SmtpEmailSender.class));
    }

    @Test
    void fallsBackToLoggingWhenThereIsNoApiKey() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(EmailSender.class)
                .hasSingleBean(LoggingEmailSender.class)
                .doesNotHaveBean(ResendEmailSender.class));
    }

    @Test
    void anEmptyApiKeyCountsAsNoApiKey() {
        runner.withPropertyValues("app.mail.resend-api-key=")
                .run(context -> assertThat(context)
                        .hasSingleBean(LoggingEmailSender.class)
                        .doesNotHaveBean(ResendEmailSender.class));
    }

    @Test
    void theFallbackLogsNoCodesOrLinksUnlessContentsAreTurnedOn() {
        runner.run(context -> {
            try (CapturedLog log = new CapturedLog(LoggingEmailSender.class)) {
                context.getBean(EmailSender.class).send(
                        "ana@example.com",
                        "Tu código",
                        "<p><strong>482917</strong></p>"
                                + "<a href=\"https://web.example/invitaciones/42\">Aceptar</a>");

                assertThat(log.onlyMessage())
                        .contains("deshabilitado")
                        .doesNotContain("482917")
                        .doesNotContain("https://web.example/invitaciones/42");
            }
        });
    }

    @Test
    void theFallbackLogsContentsWhenTheyAreTurnedOn() {
        runner.withPropertyValues("app.mail.log-contents=true").run(context -> {
            try (CapturedLog log = new CapturedLog(LoggingEmailSender.class)) {
                context.getBean(EmailSender.class)
                        .send("ana@example.com", "Tu código", "<p><strong>482917</strong></p>");

                assertThat(log.onlyMessage()).contains("Código: 482917");
            }
        });
    }

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    @Import({LoggingEmailSender.class, ResendEmailSender.class, SmtpEmailSender.class})
    static class Senders {

        @Bean
        JavaMailSender mailSender() {
            return mock(JavaMailSender.class);
        }
    }
}
