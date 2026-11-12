package com.yuyay.notification;

import com.yuyay.config.AppProperties;
import com.yuyay.notification.email.EmailSender;
import com.yuyay.notification.email.LoggingEmailSender;
import com.yuyay.notification.email.ResendEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

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
    @Import({LoggingEmailSender.class, ResendEmailSender.class})
    static class Senders {
    }
}
