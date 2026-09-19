package com.yuyay.event.listener;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.Delegation;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.DelegationRepository;
import com.yuyay.config.AppProperties;
import com.yuyay.event.CaregiverInvitedEvent;
import com.yuyay.event.DelegationCreatedEvent;
import com.yuyay.notification.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailListener {

    private final EmailService emailService;
    private final CareRelationshipRepository careRelationshipRepository;
    private final DelegationRepository delegationRepository;
    private final AppProperties appProperties;

    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCaregiverInvited(CaregiverInvitedEvent event) {
        try {
            CareRelationship rel = careRelationshipRepository
                    .findById(event.careRelationshipId())
                    .orElse(null);
            if (rel == null) {
                log.warn("CareRelationship {} no encontrada", event.careRelationshipId());
                return;
            }

            String nombreSujeto = rel.getCareSubject().getName();
            String enlace = "%s/invitations/%d".formatted(appProperties.baseUrl(), rel.getId());

            emailService.sendTemplate(
                    rel.getUser().getEmail(),
                    "Te invitaron a cuidar a %s en Yuyay".formatted(nombreSujeto),
                    "invitation",
                    Map.of(
                            "careSubjectName", nombreSujeto,
                            "invitationLink", enlace
                    ));
        } catch (Exception e) {
            log.error("Fallo enviando invitacion de la relacion {}", event.careRelationshipId(), e);
        }
    }

    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDelegationCreated(DelegationCreatedEvent event) {
        try {
            Delegation del = delegationRepository
                    .findById(event.delegationId())
                    .orElse(null);
            if (del == null) {
                log.warn("Delegation {} no encontrada", event.delegationId());
                return;
            }

            String nombreSujeto = del.getCareSubject().getName();

            emailService.sendTemplate(
                    del.getGranteeEmail(),
                    "Acceso temporal a la informacion de %s".formatted(nombreSujeto),
                    "delegation",
                    Map.of(
                            "careSubjectName", nombreSujeto,
                            "shareLink", event.shareLink()
                    ));
        } catch (Exception e) {
            log.error("Fallo enviando enlace de delegacion {}", event.delegationId(), e);
        }
    }
}
