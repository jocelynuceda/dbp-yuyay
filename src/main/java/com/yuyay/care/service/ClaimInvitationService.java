package com.yuyay.care.service;

import com.yuyay.care.dto.ClaimExchangeRequest;
import com.yuyay.care.dto.ClaimExchangeResponse;
import com.yuyay.care.dto.ClaimInvitationCreateRequest;
import com.yuyay.care.dto.ClaimInvitationResponse;
import com.yuyay.care.entity.CareAuthorizationType;
import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.entity.ClaimInvitation;
import com.yuyay.care.exception.CareSubjectNotFoundException;
import com.yuyay.care.repository.CareAuthorizationDeclarationRepository;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.care.repository.ClaimInvitationRepository;
import com.yuyay.config.AppProperties;
import com.yuyay.exception.ForbiddenCareSubjectAccessException;
import com.yuyay.exception.InvalidCareRelationshipException;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.event.ClaimInvitationCreatedEvent;
import com.yuyay.notification.entity.Notification;
import com.yuyay.notification.entity.NotificationType;
import com.yuyay.notification.repository.NotificationRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.security.TokenHasher;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Entrega de un perfil a la persona de la que habla (§3.4 del análisis).
 *
 * <p>Un solo mecanismo sirve para el menor que alcanza la edad mínima y para el adulto cuidado que
 * abre su cuenta. Al canjear, quien recibe queda `PRINCIPAL` y <b>quien entregó queda cuidador, no
 * revocado</b>: una madre que todavía gestiona al hijo no debe perder el acceso de golpe, y a
 * partir de ese momento la revocación está en manos del titular.
 *
 * <p>El historial clínico no se reescribe: cada versión conserva su autor. Que un registro diga
 * «declarado por la madre» es exactamente lo correcto.
 */
@Service
@Transactional
public class ClaimInvitationService {

    private final ClaimInvitationRepository invitations;
    private final CareSubjectRepository subjects;
    private final CareRelationshipRepository relationships;
    private final CareAuthorizationDeclarationRepository declarationRepo;
    private final CareAuthorizationDeclarationService declarationService;
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final AuthorizationService authorizationService;
    private final CurrentUserService currentUserService;
    private final AppProperties properties;
    private final ApplicationEventPublisher events;
    private final int minAge;
    private final int validDays;

    public ClaimInvitationService(
            ClaimInvitationRepository invitations,
            CareSubjectRepository subjects,
            CareRelationshipRepository relationships,
            CareAuthorizationDeclarationRepository declarationRepo,
            CareAuthorizationDeclarationService declarationService,
            NotificationRepository notifications,
            UserRepository users,
            AuthorizationService authorizationService,
            CurrentUserService currentUserService,
            AppProperties properties,
            ApplicationEventPublisher events,
            @Value("${app.care.self-claim-min-age:16}") int minAge,
            @Value("${app.care.claim-invitation-valid-days:7}") int validDays) {
        this.invitations = invitations;
        this.subjects = subjects;
        this.relationships = relationships;
        this.declarationRepo = declarationRepo;
        this.declarationService = declarationService;
        this.notifications = notifications;
        this.users = users;
        this.authorizationService = authorizationService;
        this.currentUserService = currentUserService;
        this.properties = properties;
        this.events = events;
        this.minAge = minAge;
        this.validDays = validDays;
    }

    /**
     * El enlace no vuelve en la respuesta: se envía al buzón invitado. Esa es la única prueba de que
     * quien canjea controla ese correo, porque registrarse con contraseña abre sesión sin
     * comprobarlo. Quien emite ve que la invitación existe, no la llave.
     */
    public ClaimInvitationResponse emit(Long careSubjectId, ClaimInvitationCreateRequest request) {
        Long actorId = currentUserService.getCurrentUserId();
        CareSubject subject = authorizationService.requireRoleForUpdate(actorId, careSubjectId, CareRole.PRINCIPAL);

        requireClaimable(subject, careSubjectId);
        requireMinimumAge(subject);

        User actor = users.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        // Un solo enlace vivo por perfil: reemitir invalida el anterior en lugar de acumular llaves.
        Instant now = Instant.now();
        invitations.findByCareSubjectIdAndExchangedAtIsNullAndRevokedAtIsNull(careSubjectId)
                .forEach(previous -> previous.setRevokedAt(now));

        String rawToken = TokenHasher.randomToken();
        ClaimInvitation invitation = invitations.save(ClaimInvitation.builder()
                .careSubject(subject)
                .createdBy(actor)
                .invitedEmail(request.invitedEmail().toLowerCase())
                .tokenHash(TokenHasher.sha256(rawToken))
                .validUntil(now.plus(validDays, ChronoUnit.DAYS))
                .build());

        String claimLink = properties.frontendUrl() + "/entrega/" + rawToken;
        events.publishEvent(new ClaimInvitationCreatedEvent(invitation.getId(), claimLink));
        return toResponse(invitation);
    }

    @Transactional(readOnly = true)
    public List<ClaimInvitationResponse> listBySubject(Long careSubjectId) {
        Long actorId = currentUserService.getCurrentUserId();
        authorizationService.requireRole(actorId, careSubjectId, CareRole.PRINCIPAL);
        return invitations.findByCareSubjectIdOrderByIdDesc(careSubjectId).stream()
                .map(this::toResponse)
                .toList();
    }

    public void revoke(Long careSubjectId, Long invitationId) {
        Long actorId = currentUserService.getCurrentUserId();
        authorizationService.requireRole(actorId, careSubjectId, CareRole.PRINCIPAL);

        ClaimInvitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitación de entrega no encontrada"));
        if (!invitation.getCareSubject().getId().equals(careSubjectId)) {
            throw new ResourceNotFoundException("Invitación de entrega no encontrada");
        }
        if (invitation.getExchangedAt() != null) {
            throw new InvalidOperationException("Esta invitación ya fue canjeada");
        }
        if (invitation.getRevokedAt() != null) {
            throw new InvalidOperationException("Esta invitación ya fue revocada");
        }
        invitation.setRevokedAt(Instant.now());
    }

    public ClaimExchangeResponse exchange(ClaimExchangeRequest request) {
        Long actorId = currentUserService.getCurrentUserId();
        User actor = users.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        String tokenHash = TokenHasher.sha256(request.token());

        // El bloqueo del perfil va primero y todo lo que decide la entrega se lee después de él. Si
        // la invitación se leyera antes, una revocación o una reemisión que entren en paralelo
        // quedarían fuera de la decisión y el perfil se entregaría contra la última orden dada.
        Long careSubjectId = invitations.findCareSubjectIdByTokenHash(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Este enlace de entrega no existe"));
        CareSubject subject = subjects.findForUpdateById(careSubjectId)
                .orElseThrow(() -> new CareSubjectNotFoundException(careSubjectId));

        ClaimInvitation invitation = invitations.findForUpdateByTokenHash(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Este enlace de entrega no existe"));
        if (invitation.getExchangedAt() != null) {
            throw new InvalidOperationException("Este enlace ya fue utilizado");
        }
        if (!invitation.isValidNow()) {
            throw new InvalidOperationException("Este enlace fue revocado o ya no está vigente");
        }
        if (!invitation.getInvitedEmail().equalsIgnoreCase(actor.getEmail())) {
            // El token es la llave; el correo acota a quién iba dirigida. Exigir los dos cuesta
            // poco y evita que un enlace reenviado por error entregue un historial de salud.
            throw new ForbiddenCareSubjectAccessException("Este enlace fue emitido para otra cuenta");
        }

        // Las mismas condiciones que permitieron emitir, comprobadas de nuevo aquí: entre la emisión
        // y el canje el perfil pudo declararse ficticio, cambiar de fecha de nacimiento o haberse
        // entregado ya por otro enlace.
        requireClaimable(subject, careSubjectId);
        requireMinimumAge(subject);

        Instant now = Instant.now();
        invitation.setExchangedAt(now);
        invitation.setExchangedBy(actor);
        subject.setAccountUser(actor);
        subject.setClaimedAt(now);

        promoteToPrincipal(subject, actor, now);
        demoteOtherPrincipals(subject, actorId);
        declarationService.appendSelfDeclaration(subject, actor);
        notifyCaregivers(subject, actor);

        return new ClaimExchangeResponse(subject.getId(), subject.getName(), CareRole.PRINCIPAL, now);
    }

    private void requireClaimable(CareSubject subject, Long careSubjectId) {
        if (subject.getAccountUser() != null) {
            throw new InvalidOperationException("Esta persona ya es titular de su perfil");
        }
        boolean fictitious = declarationRepo.findFirstByCareSubjectIdOrderByIdDesc(careSubjectId)
                .map(declaration -> declaration.getType() == CareAuthorizationType.FICTITIOUS)
                .orElse(false);
        if (fictitious) {
            throw new InvalidOperationException(
                    "Esta persona está declarada como ficticia: no hay nadie a quien entregarle el perfil");
        }
    }

    private void requireMinimumAge(CareSubject subject) {
        LocalDate birthDate = subject.getBirthDate();
        if (birthDate == null) {
            throw new InvalidCareRelationshipException(
                    "Registra la fecha de nacimiento de esta persona antes de entregarle su perfil");
        }
        // La fecha es declarada por el cuidador, no verificada: ver §3.5 del análisis.
        if (Period.between(birthDate, LocalDate.now()).getYears() < minAge) {
            throw new InvalidOperationException(
                    "Esta persona debe tener al menos %d años para administrar su perfil".formatted(minAge));
        }
    }

    private void promoteToPrincipal(CareSubject subject, User actor, Instant now) {
        CareRelationship mine = relationships.findByUserIdAndCareSubjectId(actor.getId(), subject.getId())
                .orElseGet(() -> relationships.save(CareRelationship.builder()
                        .user(actor)
                        .careSubject(subject)
                        .role(CareRole.PRINCIPAL)
                        .status(CareStatus.ACTIVE)
                        .invitedAt(now)
                        .build()));
        mine.setRole(CareRole.PRINCIPAL);
        mine.setStatus(CareStatus.ACTIVE);
        mine.setRevokedAt(null);
        if (mine.getAcceptedAt() == null) {
            mine.setAcceptedAt(now);
        }
    }

    /** El titular queda como único PRINCIPAL; los demás conservan acceso como cuidadores. */
    private void demoteOtherPrincipals(CareSubject subject, Long titularId) {
        relationships.findByCareSubjectId(subject.getId()).stream()
                .filter(relationship -> !relationship.getUser().getId().equals(titularId))
                .filter(relationship -> relationship.getStatus() == CareStatus.ACTIVE)
                .filter(relationship -> relationship.getRole() == CareRole.PRINCIPAL)
                .forEach(relationship -> relationship.setRole(CareRole.CAREGIVER));
    }

    private void notifyCaregivers(CareSubject subject, User titular) {
        relationships.findByCareSubjectId(subject.getId()).stream()
                .filter(relationship -> relationship.getStatus() == CareStatus.ACTIVE)
                .filter(relationship -> !relationship.getUser().getId().equals(titular.getId()))
                .forEach(relationship -> notifications.save(Notification.builder()
                        .user(relationship.getUser())
                        .type(NotificationType.PROFILE_CLAIMED)
                        .careSubjectId(subject.getId())
                        .title("%s ahora administra su perfil".formatted(subject.getName()))
                        .body("%s accedió a su historial en Yuyay. Tu acceso continúa como cuidador y ahora %s puede decidir sobre él."
                                .formatted(titular.getName(), titular.getName()))
                        .build()));
    }

    private ClaimInvitationResponse toResponse(ClaimInvitation invitation) {
        return new ClaimInvitationResponse(
                invitation.getId(),
                invitation.getCareSubject().getId(),
                invitation.getInvitedEmail(),
                invitation.getValidUntil(),
                invitation.getExchangedAt(),
                invitation.getRevokedAt(),
                invitation.isPending());
    }
}
