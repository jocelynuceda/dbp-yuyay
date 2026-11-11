package com.yuyay.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yuyay.notification.email.LoggingEmailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingEmailSenderTest {

    private final LoggingEmailSender sender = new LoggingEmailSender();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void logsRecipientAndSubject() {
        sender.send("ana@example.com", "Bienvenida a Yuyay", "<p>Hola Ana</p>");

        assertThat(logged()).contains("ana@example.com").contains("Bienvenida a Yuyay");
    }

    @Test
    void logsTheVerificationCode() {
        sender.send("ana@example.com", "Tu código", "<p><strong>482917</strong></p>");

        assertThat(logged()).contains("Código: 482917");
    }

    @Test
    void ignoresNumbersThatAreNotTextOfTheEmail() {
        sender.send("ana@example.com", "Bienvenida", "<p style=\"width: 123456px\">Hola</p>");

        assertThat(logged()).doesNotContain("Código");
    }

    @Test
    void logsEachLinkOnce() {
        sender.send(
                "ana@example.com",
                "Te invitaron",
                "<a href=\"https://web.example/invitaciones/42\">Aceptar</a>"
                        + "<a href=\"https://web.example/invitaciones/42\">o aquí</a>");

        assertThat(logged()).contains("Enlace: https://web.example/invitaciones/42");
    }

    @Test
    void logsBothLinksWhenThereAreTwo() {
        sender.send(
                "ana@example.com",
                "Acceso compartido",
                "<a href=\"https://web.example/a\">A</a><a href=\"https://web.example/b\">B</a>");

        assertThat(logged()).contains("Enlaces: https://web.example/a, https://web.example/b");
    }

    private String logged() {
        assertThat(appender.list).hasSize(1);
        return appender.list.getFirst().getFormattedMessage();
    }
}
