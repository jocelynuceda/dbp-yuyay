package com.yuyay.consultation.service;

import com.yuyay.consultation.dto.HandoffResumedViewDTO;
import com.yuyay.audit.entity.AccessAction;
import com.yuyay.event.AccessRecordedEvent;
import com.yuyay.consultation.entity.HandoffSession;
import com.yuyay.consultation.mapper.HandoffMapper;
import com.yuyay.consultation.repository.HandoffRepository;
import com.yuyay.consultation.repository.HandoffSessionRepository;
import com.yuyay.exception.HandoffSessionExpiredException;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.security.TokenHasher;
import com.yuyay.security.handoff.HandoffAccessGrant;
import com.yuyay.security.handoff.HandoffAccessGrantRepository;
import com.yuyay.security.handoff.HandoffCodeException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HandoffReadingService {
    private final HandoffSessionRepository sessions;
    private final HandoffRepository handoffs;
    private final HandoffAccessGrantRepository grants;
    private final HandoffMapper mapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public Instant consent(String token, String explicitGrant) {
        Instant now = clock.instant();
        HandoffSession session = activeSession(token, now);
        if (!session.isAccessCodeRequired()) {
            throw new InvalidOperationException("Este enlace no necesita una cookie de lectura");
        }
        return validGrant(session, explicitGrant, now).map(grant -> expiresAt(session, grant))
                .orElseThrow(() -> new HandoffCodeException(HttpStatus.FORBIDDEN, "HANDOFF_ACCESS_GRANT_INVALID",
                        "Abre el resumen con el código antes de guardar el permiso de lectura"));
    }

    public Optional<HandoffResumedViewDTO> resume(String token, String cookieGrant) {
        Instant now = clock.instant();
        HandoffSession session = activeSession(token, now);
        Optional<HandoffAccessGrant> grant = validGrant(session, cookieGrant, now);
        if (grant.isEmpty()) return Optional.empty();
        var handoff = handoffs.findDetailById(session.getHandoff().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Handoff no encontrado"));
        HandoffAccessGrant reader = grant.get();
        String role = reader.getVisitorRole();
        if (role != null && role.codePointCount(0, role.length()) > 60) {
            role = role.substring(0, role.offsetByCodePoints(0, 60));
        }
        events.publishEvent(AccessRecordedEvent.byVisitor(handoff.getCareSubject().getId(), AccessAction.VIEW,
                "HANDOFF", handoff.getId(), reader.getVisitorName(), role));
        return Optional.of(new HandoffResumedViewDTO(handoff.getId(), session.displaySubjectName(),
                handoff.getReason(), handoff.getQuestions(), handoff.getCreatedAt(),
                handoff.getItems().stream().map(mapper::toItemResponse).toList(),
                expiresAt(session, reader), now, session.isDownloadAllowed()));
    }

    private Optional<HandoffAccessGrant> validGrant(HandoffSession session, String rawGrant, Instant now) {
        if (!session.isAccessCodeRequired() || session.getFirstOpenedAt() == null
                || rawGrant == null || rawGrant.isBlank() || rawGrant.length() > 128) return Optional.empty();
        return grants.findBySessionIdAndTokenHashAndExpiresAtAfter(session.getId(), TokenHasher.sha256(rawGrant), now);
    }

    private Instant expiresAt(HandoffSession session, HandoffAccessGrant grant) {
        return grant.getExpiresAt().isBefore(session.accessExpiresAt()) ? grant.getExpiresAt() : session.accessExpiresAt();
    }

    private HandoffSession activeSession(String token, Instant now) {
        HandoffSession session = sessions.findByTokenHash(TokenHasher.sha256(token))
                .orElseThrow(() -> new ResourceNotFoundException("Enlace de Handoff no encontrado"));
        if (session.isExpired(now)) {
            throw new HandoffSessionExpiredException("El enlace de Handoff expiró o fue revocado");
        }
        return session;
    }
}
