package com.yuyay.notification.email;

import com.yuyay.config.AppProperties;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Envío por SMTP")
class SmtpEmailSenderTest {

    private static final String ACCOUNT = "yuyay.pruebas@gmail.com";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private SmtpEmailSender sender;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((jakarta.mail.Session) null));
        sender = new SmtpEmailSender(mailSender, properties("Yuyay <no-reply@yuyay.app>"), ACCOUNT);
    }

    @Test
    @DisplayName("un correo rechazado se le avisa a quien lo pidió")
    void aRejectedEmailIsReportedToTheCaller() {
        doThrow(new MailSendException("550 rechazado")).when(mailSender).send(any(MimeMessage.class));

        // Sin esto, un recordatorio que el servidor rechazó quedaría marcado como enviado y no se
        // reintentaría nunca.
        assertThatThrownBy(() -> sender.send("ana@example.com", "Asunto", "<p>Hola</p>"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasRootCauseInstanceOf(MailSendException.class);
    }

    @Test
    @DisplayName("un correo aceptado no interrumpe a quien lo pidió")
    void anAcceptedEmailDoesNotInterruptTheCaller() {
        sender.send("ana@example.com", "Asunto", "<p>Hola</p>");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("el remitente es la cuenta autenticada cuando MAIL_FROM apunta a otra dirección")
    void theSenderIsTheAuthenticatedAccountWhenMailFromPointsElsewhere() throws Exception {
        sender.send("ana@example.com", "Asunto", "<p>Hola</p>");

        // Gmail reescribe un remitente que no sea el suyo: mejor mandarlo ya correcto.
        assertThat(sentMessage().getFrom()[0].toString()).contains(ACCOUNT);
    }

    @Test
    @DisplayName("se respeta MAIL_FROM cuando sí es la cuenta autenticada")
    void mailFromIsKeptWhenItIsTheAuthenticatedAccount() throws Exception {
        sender = new SmtpEmailSender(mailSender, properties("Yuyay <" + ACCOUNT + ">"), ACCOUNT);

        sender.send("ana@example.com", "Asunto", "<p>Hola</p>");

        assertThat(sentMessage().getFrom()[0].toString()).isEqualTo("Yuyay <" + ACCOUNT + ">");
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(message.capture());
        return message.getValue();
    }

    private static AppProperties properties(String from) {
        return new AppProperties(null, null, null, null, new AppProperties.Mail(from, null), null);
    }
}
