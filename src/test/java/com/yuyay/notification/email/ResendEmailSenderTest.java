package com.yuyay.notification.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** El adaptador real de Resend debe avisar del fallo a quien envía, no solo registrarlo en el log. */
class ResendEmailSenderTest {

    @Test
    void unErrorDeResendLlegaAQuienEnvia() throws Exception {
        Resend resend = mock(Resend.class);
        Emails emails = mock(Emails.class);
        when(resend.emails()).thenReturn(emails);
        when(emails.send(any(CreateEmailOptions.class))).thenThrow(new ResendException("rechazado"));

        ResendEmailSender sender = new ResendEmailSender(resend, "Yuyay <avisos@yuyay.app>");

        assertThatThrownBy(() -> sender.send("ana@yuyay.app", "Asunto", "<p>Hola</p>"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasCauseInstanceOf(ResendException.class);
    }

    @Test
    void unEnvioCorrectoNoLanzaNada() throws Exception {
        Resend resend = mock(Resend.class);
        Emails emails = mock(Emails.class);
        when(resend.emails()).thenReturn(emails);

        ResendEmailSender sender = new ResendEmailSender(resend, "Yuyay <avisos@yuyay.app>");

        assertThatCode(() -> sender.send("ana@yuyay.app", "Asunto", "<p>Hola</p>")).doesNotThrowAnyException();
    }
}
