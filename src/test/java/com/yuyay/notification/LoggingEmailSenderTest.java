package com.yuyay.notification;

import com.yuyay.notification.email.LoggingEmailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingEmailSenderTest {

    private static final String CODE_EMAIL = "<p><strong>482917</strong></p>";
    private static final String LINK_EMAIL =
            "<a href=\"https://web.example/invitaciones/42\">Aceptar</a>";

    private CapturedLog log;

    @BeforeEach
    void setUp() {
        log = new CapturedLog(LoggingEmailSender.class);
    }

    @AfterEach
    void tearDown() {
        log.close();
    }

    @Test
    void logsRecipientAndSubject() {
        verbose().send("ana@example.com", "Bienvenida a Yuyay", "<p>Hola Ana</p>");

        assertThat(log.onlyMessage()).contains("ana@example.com").contains("Bienvenida a Yuyay");
    }

    @Test
    void logsTheVerificationCode() {
        verbose().send("ana@example.com", "Tu código", CODE_EMAIL);

        assertThat(log.onlyMessage()).contains("Código: 482917");
    }

    @Test
    void ignoresNumbersThatAreNotTextOfTheEmail() {
        verbose().send("ana@example.com", "Bienvenida", "<p style=\"width: 123456px\">Hola</p>");

        assertThat(log.onlyMessage()).doesNotContain("Código");
    }

    @Test
    void logsEachLinkOnce() {
        verbose().send(
                "ana@example.com",
                "Te invitaron",
                LINK_EMAIL + "<a href=\"https://web.example/invitaciones/42\">o aquí</a>");

        assertThat(log.onlyMessage()).contains("Enlace: https://web.example/invitaciones/42");
    }

    @Test
    void logsBothLinksWhenThereAreTwo() {
        verbose().send(
                "ana@example.com",
                "Acceso compartido",
                "<a href=\"https://web.example/a\">A</a><a href=\"https://web.example/b\">B</a>");

        assertThat(log.onlyMessage()).contains("Enlaces: https://web.example/a, https://web.example/b");
    }

    @Test
    void doesNotLogTheVerificationCodeWhenContentsAreOff() {
        quiet().send("ana@example.com", "Tu código", CODE_EMAIL);

        assertThat(log.onlyMessage())
                .contains("deshabilitado")
                .contains("ana@example.com")
                .doesNotContain("482917")
                .doesNotContain("Código:");
    }

    @Test
    void doesNotLogLinksWhenContentsAreOff() {
        quiet().send("ana@example.com", "Te invitaron", LINK_EMAIL);

        assertThat(log.onlyMessage())
                .doesNotContain("https://web.example/invitaciones/42")
                .doesNotContain("Enlace");
    }

    private LoggingEmailSender verbose() {
        return new LoggingEmailSender(true);
    }

    private LoggingEmailSender quiet() {
        return new LoggingEmailSender(false);
    }
}
