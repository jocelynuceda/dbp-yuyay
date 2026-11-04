package com.yuyay.event.listener;

import com.yuyay.event.PasswordResetRequestedEvent;
import com.yuyay.notification.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetEmailListener {

    private final EmailService emailService;

    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        try {
            emailService.sendTemplate(
                    event.email(),
                    "Tu código para restablecer la contraseña de Yuyay",
                    "password-reset",
                    Map.of(
                            "userName", event.userName(),
                            "code", event.code(),
                            "minutesValid", event.minutesValid()
                    ));
        } catch (Exception e) {
            log.error("Fallo enviando el código de recuperación", e);
        }
    }
}
