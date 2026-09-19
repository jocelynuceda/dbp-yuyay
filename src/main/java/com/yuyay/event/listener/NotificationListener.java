package com.yuyay.event.listener;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.event.HealthEntryChangedEvent;
import com.yuyay.notification.email.EmailService;
import com.yuyay.notification.entity.Notification;
import com.yuyay.notification.entity.NotificationType;
import com.yuyay.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final CareRelationshipRepository careRelationshipRepository;
    private final NotificationRepository notificationRepository;
    private final EmailService emailService;

    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onHealthEntryChanged(HealthEntryChangedEvent event) {
        try {
            List<CareRelationship> cuidadores = careRelationshipRepository
                    .findByCareSubjectIdAndStatus(event.careSubjectId(), CareStatus.ACTIVE);

            for (CareRelationship rel : cuidadores) {
                var destinatario = rel.getUser();
                if (destinatario.getId().equals(event.actorUserId())) {
                    continue;   // el autor del cambio no se notifica a sí mismo
                }

                notificationRepository.save(Notification.builder()
                        .user(destinatario)
                        .type(NotificationType.ENTRY_CHANGED)
                        .careSubjectId(event.careSubjectId())
                        .title("Cambio en %s".formatted(event.careSubjectName()))
                        .body(event.changeDescription())
                        .build());

                // el correo no debe tumbar la notificación ya guardada
                try {
                    emailService.sendTemplate(
                            destinatario.getEmail(),
                            "Yuyay · cambio en %s".formatted(event.careSubjectName()),
                            "entry-changed",
                            Map.of(
                                    "careSubjectName", event.careSubjectName(),
                                    "changeDescription", event.changeDescription()
                            ));
                } catch (Exception e) {
                    log.warn("No se pudo enviar el correo de cambio a {}", destinatario.getEmail(), e);
                }
            }
        } catch (Exception e) {
            log.error("Fallo procesando HealthEntryChangedEvent {}", event, e);   // nunca relanzar
        }
    }
}