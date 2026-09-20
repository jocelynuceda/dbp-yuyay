package com.yuyay.consultation.service;

import com.yuyay.audit.entity.AccessAction;
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
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        HandoffSession session = HandoffSession.builder()
                .handoff(handoff)
                .tokenHash(TokenHasher.sha256(rawToken))
                .expiresAt(
                        Instant.now().plus(
                                request.expiresInHours(),
                                ChronoUnit.HOURS
                        )
                )
                .sessionWindowMinutes(request.sessionWindowMinutes())
                .build();

        HandoffSession saved = handoffSessionRepository.save(session);

        return new HandoffSessionCreatedDTO(
                saved.getId(),
                rawToken,
                saved.getExpiresAt(),
                saved.getSessionWindowMinutes(),
                saved.getCreatedAt()
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

        HandoffSession session = handoffSessionRepository.findById(sessionId)
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
            session.setRevokedAt(Instant.now());
        }
    }

    public HandoffPublicViewDTO open(
            String rawToken,
            String visitorName,
            String visitorRole
    ) {

        String tokenHash = TokenHasher.sha256(rawToken);

        HandoffSession session = handoffSessionRepository
                .findByTokenHash(tokenHash)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Enlace de Handoff no encontrado"
                        ));

        if (session.isExpired()) {
            throw new HandoffSessionExpiredException(
                    "El enlace de Handoff expiró o fue revocado"
            );
        }

        if (session.getFirstOpenedAt() == null) {
            session.setFirstOpenedAt(Instant.now());
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
                handoff.getCareSubject().getName(),
                handoff.getReason(),
                handoff.getQuestions(),
                handoff.getCreatedAt(),
                handoff.getItems()
                        .stream()
                        .map(handoffMapper::toItemResponse)
                        .toList()
        );
    }

    private void authorizeCreatorOrPrincipal(
            Handoff handoff,
            Long currentUserId
    ) {

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
                session.getCreatedAt()
        );
    }
}