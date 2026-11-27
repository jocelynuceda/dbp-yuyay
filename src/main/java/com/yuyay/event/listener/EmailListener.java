package com.yuyay.event.listener;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.ClaimInvitation;
import com.yuyay.care.entity.Delegation;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.ClaimInvitationRepository;
import com.yuyay.care.repository.DelegationRepository;
import com.yuyay.config.AppProperties;
import com.yuyay.event.CaregiverInvitedEvent;
import com.yuyay.event.ClaimInvitationCreatedEvent;
import com.yuyay.event.DelegationCreatedEvent;
import com.yuyay.event.UserRegisteredEvent;
import com.yuyay.notification.email.EmailService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailListener {

    /** Fecha en la zona en la que vive la app, la misma que usan los recordatorios. */
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of("America/Lima"));

    private final EmailService emailService;
    private final CareRelationshipRepository careRelationshipRepository;
    private final DelegationRepository delegationRepository;
    private final ClaimInvitationRepository claimInvitationRepository;
    private final UserRepository userRepository;
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
            String enlace = "%s/invitaciones/%d".formatted(appProperties.frontendUrl(), rel.getId());

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

    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserRegistered(UserRegisteredEvent event) {
        try {
            User user = userRepository
                    .findById(event.userId())
                    .orElse(null);
            if (user == null) {
                log.warn("User {} no encontrado", event.userId());
                return;
            }

            emailService.sendTemplate(
                    user.getEmail(),
                    "Te damos la bienvenida a Yuyay",
                    "welcome",
                    Map.of(
                            "userName", user.getName(),
                            "appLink", appProperties.frontendUrl()
                    ));
        } catch (Exception e) {
            log.error("Fallo enviando bienvenida al usuario {}", event.userId(), e);
        }
    }

    /**
     * El enlace de entrega solo sale por aquí. Quien emite la invitación no lo recibe: que llegue al
     * buzón invitado es lo que acredita ese correo, porque abrir una cuenta con contraseña da sesión
     * sin comprobarlo.
     */
    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onClaimInvitationCreated(ClaimInvitationCreatedEvent event) {
        try {
            ClaimInvitation invitation = claimInvitationRepository
                    .findById(event.invitationId())
                    .orElse(null);
            if (invitation == null) {
                log.warn("ClaimInvitation {} no encontrada", event.invitationId());
                return;
            }

            emailService.sendTemplate(
                    invitation.getInvitedEmail(),
                    "Te entregan tu perfil en Yuyay",
                    "profile-claim",
                    Map.of(
                            "careSubjectName", invitation.getCareSubject().getName(),
                            "handedOverBy", invitation.getCreatedBy().getName(),
                            "claimLink", event.claimLink(),
                            "validUntil", DAY.format(invitation.getValidUntil())
                    ));
        } catch (Exception e) {
            log.error("Fallo enviando el enlace de entrega {}", event.invitationId(), e);
        }
    }
}
