package com.yuyay.consultation.service;

import com.yuyay.attachment.dto.AttachmentContent;
import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.storage.FileStorage;
import com.yuyay.audit.entity.AccessAction;
import com.yuyay.audit.entity.AccessLog;
import com.yuyay.audit.repository.AccessLogRepository;
import com.yuyay.care.entity.CareRole;
import com.yuyay.consultation.dto.CreateHandoffSessionDTO;
import com.yuyay.consultation.dto.HandoffPublicViewDTO;
import com.yuyay.consultation.dto.HandoffSessionCreatedDTO;
import com.yuyay.consultation.dto.HandoffSessionResponseDTO;
import com.yuyay.consultation.entity.Handoff;
import com.yuyay.consultation.entity.HandoffSession;
import com.yuyay.consultation.mapper.HandoffMapper;
import com.yuyay.consultation.repository.HandoffRepository;
import com.yuyay.consultation.repository.HandoffSessionRepository;
import com.yuyay.event.AccessRecordedEvent;
import com.yuyay.exception.HandoffSessionExpiredException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.health.entity.HealthEntryVersion;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.security.TokenHasher;
import com.yuyay.security.handoff.HandoffAccessCodeDTO;
import com.yuyay.security.handoff.HandoffAccessGrant;
import com.yuyay.security.handoff.HandoffAccessSecurityService;
import com.yuyay.security.handoff.HandoffCodeException;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class HandoffSessionService {

    private final HandoffSessionRepository handoffSessionRepository;
    private final HandoffRepository handoffRepository;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;
    private final HandoffMapper handoffMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final FileStorage fileStorage;
    private final HandoffAccessSecurityService accessSecurity;
    private final Clock clock;
    private final HandoffSummaryPdf summaryPdf;
    private final AccessLogRepository accessLogs;
    private final UserRepository users;

    public HandoffSessionCreatedDTO create(
            Long handoffId,
            CreateHandoffSessionDTO request
    ) {

        Long currentUserId = currentUserService.getCurrentUserId();

        Handoff handoff = handoffRepository.findDetailById(handoffId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Handoff no encontrado"));

        authorizeCreatorOrPrincipal(handoff, currentUserId);

        String rawToken = TokenHasher.randomToken();
        Instant now = clock.instant();

        HandoffSession session = HandoffSession.builder()
                .handoff(handoff)
                .tokenHash(TokenHasher.sha256(rawToken))
                .expiresAt(now.plus(request.linkDurationMinutes(), ChronoUnit.MINUTES))
                .sessionWindowMinutes(request.sessionWindowMinutes())
                .accessCodeRequired(Boolean.TRUE.equals(request.requireAccessCode()))
                .downloadAuthorizedAt(Boolean.TRUE.equals(request.allowDownload()) ? now : null)
                .subjectNameSnapshot(handoff.getCareSubject().getName())
                .build();

        HandoffSession saved = handoffSessionRepository.save(session);
        accessSecurity.initialize(saved);
        if (saved.isDownloadAllowed()) {
            accessLogs.save(AccessLog.builder()
                    .careSubject(handoff.getCareSubject())
                    .user(users.getReferenceById(currentUserId))
                    .action(AccessAction.CREATE)
                    .targetType("HANDOFF_DOWNLOAD_PERMISSION")
                    .targetId(saved.getId())
                    .occurredAt(saved.getDownloadAuthorizedAt())
                    .build());
        }

        return new HandoffSessionCreatedDTO(
                saved.getId(),
                rawToken,
                saved.getExpiresAt(),
                saved.getSessionWindowMinutes(),
                saved.getCreatedAt(),
                saved.isAccessCodeRequired(),
                saved.isDownloadAllowed()
        );
    }

    @Transactional(readOnly = true)
    public List<HandoffSessionResponseDTO> list(Long handoffId) {

        Long currentUserId = currentUserService.getCurrentUserId();

        Handoff handoff = handoffRepository.findDetailById(handoffId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Handoff no encontrado"));

        authorizeCreatorOrPrincipal(handoff, currentUserId);

        return handoffSessionRepository
                .findByHandoffIdOrderByCreatedAtDesc(handoffId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public void revoke(Long sessionId) {

        Long currentUserId = currentUserService.getCurrentUserId();

        HandoffSession session = handoffSessionRepository.findForUpdateById(sessionId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Sesión de Handoff no encontrada"
                        ));

        Handoff handoff = handoffRepository
                .findDetailById(session.getHandoff().getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Handoff no encontrado"));

        authorizeCreatorOrPrincipal(handoff, currentUserId);

        if (session.getRevokedAt() == null) {
            session.setRevokedAt(clock.instant());
        }
    }

    @Transactional(noRollbackFor = HandoffCodeException.class)
    public HandoffPublicViewDTO open(
            String rawToken,
            String visitorName,
            String visitorRole
    ) {
        return open(rawToken, visitorName, visitorRole, null);
    }

    @Transactional(noRollbackFor = HandoffCodeException.class)
    public HandoffPublicViewDTO open(String rawToken, String visitorName, String visitorRole, String accessCode) {

        HandoffSession session = requireActiveSession(rawToken);
        HandoffAccessSecurityService.IssuedGrant grant = null;
        if (session.isAccessCodeRequired() || session.isDownloadAllowed()) {
            grant = accessSecurity.verifyAndIssue(session.getId(), accessCode, visitorName, visitorRole);
        } else if (session.getFirstOpenedAt() == null) {
            session.setFirstOpenedAt(clock.instant());
        }

        Handoff handoff = handoffRepository
                .findDetailById(session.getHandoff().getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Handoff no encontrado"));

        eventPublisher.publishEvent(
                AccessRecordedEvent.byVisitor(
                        handoff.getCareSubject().getId(),
                        AccessAction.OPEN_HANDOFF_LINK,
                        "HANDOFF",
                        handoff.getId(),
                        visitorName,
                        visitorRole
                )
        );

        return new HandoffPublicViewDTO(
                handoff.getId(),
                session.displaySubjectName(),
                handoff.getReason(),
                handoff.getQuestions(),
                handoff.getCreatedAt(),
                handoff.getItems()
                        .stream()
                        .map(handoffMapper::toItemResponse)
                        .toList(),
                grant == null ? null : grant.token(),
                grant == null ? null : grant.expiresAt(),
                clock.instant(),
                session.isDownloadAllowed()
        );
    }

    public byte[] summaryPdf(String rawToken, String accessGrant) {
        // Comparte el bloqueo de revocación hasta terminar la descarga y su auditoría.
        HandoffSession session = handoffSessionRepository.findForUpdateByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(() -> new ResourceNotFoundException("Enlace de Handoff no encontrado"));
        requireActiveSession(session, clock.instant());
        if (!session.isDownloadAllowed()) {
            throw new AccessDeniedException("El cuidador no autorizó la descarga de este resumen");
        }
        HandoffAccessGrant grant = accessSecurity.requireDownloadGrant(session, accessGrant);
        Handoff handoff = handoffRepository.findDetailById(session.getHandoff().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Handoff no encontrado"));
        byte[] pdf = summaryPdf.render(handoff, session.displaySubjectName(), session.getDownloadAuthorizedAt());
        Instant now = clock.instant();
        requireActiveSession(session, now);
        if (!grant.getExpiresAt().isAfter(now)) {
            throw new HandoffSessionExpiredException("El permiso de descarga expiró");
        }
        String role = grant.getVisitorRole();
        if (role != null && role.codePointCount(0, role.length()) > 60) {
            role = role.substring(0, role.offsetByCodePoints(0, 60));
        }
        accessLogs.save(AccessLog.builder()
                .careSubject(handoff.getCareSubject())
                .action(AccessAction.VIEW)
                .targetType("HANDOFF_SUMMARY_PDF")
                .targetId(session.getId())
                .visitorName(grant.getVisitorName())
                .visitorRole(role)
                .occurredAt(now)
                .build());
        return pdf;
    }

    @Transactional(readOnly = true)
    public AttachmentContent sourceContent(String rawToken, Long versionId) {
        return sourceContent(rawToken, versionId, null);
    }

    @Transactional(readOnly = true)
    public AttachmentContent sourceContent(String rawToken, Long versionId, String accessGrant) {
        HandoffSession session = requireActiveSession(rawToken);
        accessSecurity.requireGrant(session, accessGrant);
        if (session.getFirstOpenedAt() == null) {
            throw new AccessDeniedException("Abre primero el resumen e identifica al visitante");
        }

        Handoff handoff = handoffRepository.findDetailById(session.getHandoff().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Handoff no encontrado"));
        // Resolver siempre desde el snapshot compartido, nunca desde la última versión del registro.
        HealthEntryVersion version = handoff.getItems().stream()
                .map(item -> item.getHealthEntryVersion())
                .filter(candidate -> candidate.getId().equals(versionId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Documento no incluido en este resumen"));
        Attachment attachment = version.getSourceAttachment();
        Long subjectId = handoff.getCareSubject().getId();
        if (attachment == null
                || !subjectId.equals(version.getHealthEntry().getCareSubject().getId())
                || !subjectId.equals(attachment.getCareSubject().getId())) {
            throw new ResourceNotFoundException("Documento no incluido en este resumen");
        }
        return new AttachmentContent(fileStorage.load(attachment.getStorageKey()),
                attachment.getContentType(), attachment.getOriginalFilename());
    }

    @Transactional(readOnly = true)
    public boolean accessCodeRequired(String rawToken) {
        return requireActiveSession(rawToken).isAccessCodeRequired();
    }

    private HandoffSession requireActiveSession(String rawToken) {
        HandoffSession session = handoffSessionRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(() -> new ResourceNotFoundException("Enlace de Handoff no encontrado"));
        requireActiveSession(session, clock.instant());
        return session;
    }

    private void requireActiveSession(HandoffSession session, Instant now) {
        if (session.isExpired(now)) {
            throw new HandoffSessionExpiredException("El enlace de Handoff expiró o fue revocado");
        }
    }

    private void authorizeCreatorOrPrincipal(
            Handoff handoff,
            Long currentUserId
    ) {

        authorizationService.requireAccess(currentUserId, handoff.getCareSubject().getId());

        if (handoff.getCreatedBy().getId().equals(currentUserId)) {
            return;
        }

        authorizationService.requireRole(
                currentUserId,
                handoff.getCareSubject().getId(),
                CareRole.PRINCIPAL
        );
    }

    private HandoffSessionResponseDTO toResponse(
            HandoffSession session
    ) {
        return new HandoffSessionResponseDTO(
                session.getId(),
                session.getHandoff().getId(),
                session.getExpiresAt(),
                session.getSessionWindowMinutes(),
                session.getFirstOpenedAt(),
                session.getRevokedAt(),
                session.getCreatedAt(),
                session.isAccessCodeRequired(),
                session.isDownloadAllowed()
        );
    }

    @Transactional(readOnly = true)
    public HandoffAccessCodeDTO accessCode(Long sessionId) {
        HandoffSession session = handoffSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Sesión de Handoff no encontrada"));
        authorizeCreatorOrPrincipal(session.getHandoff(), currentUserService.getCurrentUserId());
        return accessSecurity.currentCode(session);
    }
}
