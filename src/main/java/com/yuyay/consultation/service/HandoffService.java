package com.yuyay.consultation.service;

import com.yuyay.care.entity.CareSubject;
import com.yuyay.consultation.dto.CreateHandoffDTO;
import com.yuyay.consultation.dto.HandoffDetailDTO;
import com.yuyay.consultation.dto.HandoffResponseDTO;
import com.yuyay.consultation.entity.Handoff;
import com.yuyay.consultation.entity.HandoffItem;
import com.yuyay.consultation.mapper.HandoffMapper;
import com.yuyay.consultation.repository.HandoffRepository;
import com.yuyay.exception.InvalidHandoffException;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.health.entity.HealthEntry;
import com.yuyay.health.entity.HealthEntryVersion;
import com.yuyay.health.repository.HealthEntryRepository;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.security.TokenHasher;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class HandoffService {

    private final HandoffRepository handoffRepository;
    private final EntityManager entityManager;
    private final HealthEntryRepository healthEntryRepository;
    private final HealthEntryVersionRepository healthEntryVersionRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;
    private final HandoffMapper handoffMapper;

    public HandoffDetailDTO create(Long careSubjectId, CreateHandoffDTO request) {

        Long currentUserId = currentUserService.getCurrentUserId();

        String requestId = request.requestId() == null ? null : request.requestId().toString();
        // Misma exclusión por cuenta que la confirmación desde cita, incluso antes del primer INSERT.
        User currentUser = requestId == null ? userRepository.getReferenceById(currentUserId)
                : userRepository.findForUpdateById(currentUserId)
                        .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        CareSubject careSubject = authorizationService.requireAccessForUpdate(currentUserId, careSubjectId);

        Set<Long> uniqueIds = new HashSet<>(request.healthEntryIds());

        if (uniqueIds.size() != request.healthEntryIds().size()) {
            throw new InvalidHandoffException(
                    "No se pueden incluir registros de salud repetidos"
            );
        }

        if (request.expectedVersionIds() != null
                && (!request.expectedVersionIds().keySet().equals(uniqueIds)
                || request.expectedVersionIds().values().stream().anyMatch(java.util.Objects::isNull))) {
            throw new InvalidHandoffException("Debes indicar la versión revisada de cada registro seleccionado");
        }

        String fingerprint = null;
        if (requestId != null) {
            fingerprint = manualFingerprint(careSubjectId, request);
            var previous = handoffRepository.findByCreatedByIdAndConfirmationRequestId(currentUserId, requestId);
            if (previous.isPresent()) {
                if (!fingerprint.equals(previous.get().getConfirmationFingerprint())) {
                    throw new InvalidOperationException("La clave ya fue utilizada con otro contenido");
                }
                return handoffMapper.toDetail(previous.get());
            }
        }

        List<HealthEntry> entries =
                healthEntryRepository.findByIdInAndCareSubjectId(
                        uniqueIds,
                        careSubjectId
                );

        if (entries.size() != uniqueIds.size()) {
            throw new InvalidHandoffException(
                    "Uno o más registros de salud no pertenecen a esta persona"
            );
        }

        if (entries.stream().anyMatch(HealthEntry::isDeleted)) {
            throw new InvalidHandoffException(
                    "No se pueden incluir registros de salud eliminados"
            );
        }

        Handoff handoff = Handoff.builder()
                .careSubject(careSubject)
                .createdBy(currentUser)
                .reason(request.reason())
                .questions(request.questions())
                .confirmationRequestId(requestId)
                .confirmationFingerprint(fingerprint)
                .build();

        for (Long entryId : request.healthEntryIds()) {

            HealthEntryVersion latestVersion =
                    healthEntryVersionRepository
                            .findFirstByHealthEntryIdOrderByVersionNumberDesc(
                                    entryId
                            )
                            .orElseThrow(() ->
                                    new InvalidHandoffException(
                                            "El registro de salud "
                                                    + entryId
                                                    + " no tiene versiones"
                                    ));

            if (request.expectedVersionIds() != null
                    && !latestVersion.getId().equals(request.expectedVersionIds().get(entryId))) {
                throw new InvalidOperationException(
                        "Un registro cambió desde que lo revisaste. Revisa el resumen antes de compartirlo");
            }

            HandoffItem item = HandoffItem.builder()
                    .handoff(handoff)
                    .healthEntryVersion(latestVersion)
                    .position(handoff.getItems().size())
                    .build();

            handoff.getItems().add(item);
        }

        Handoff saved = handoffRepository.saveAndFlush(handoff);
        entityManager.refresh(saved);
        return handoffMapper.toDetail(saved);
    }

    private static String manualFingerprint(Long subjectId, CreateHandoffDTO request) {
        StringBuilder content = new StringBuilder("manual|").append(subjectId).append('|');
        appendText(content, request.reason());
        appendText(content, request.questions());
        for (Long id : request.healthEntryIds()) {
            content.append('|').append(id);
            if (request.expectedVersionIds() != null) content.append(':').append(request.expectedVersionIds().get(id));
        }
        return TokenHasher.sha256(content.toString());
    }

    private static void appendText(StringBuilder content, String value) {
        if (value == null) content.append("-1:");
        else content.append(value.length()).append(':').append(value);
    }

    @Transactional(readOnly = true)
    public List<HandoffResponseDTO> list(Long careSubjectId) {

        Long currentUserId = currentUserService.getCurrentUserId();

        authorizationService.requireAccess(currentUserId, careSubjectId);

        return handoffRepository
                .findByCareSubjectIdOrderByCreatedAtDesc(careSubjectId)
                .stream()
                .map(handoffMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public HandoffDetailDTO get(Long handoffId) {

        Long currentUserId = currentUserService.getCurrentUserId();

        Handoff handoff = handoffRepository.findDetailById(handoffId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Handoff no encontrado"));

        authorizationService.requireAccess(
                currentUserId,
                handoff.getCareSubject().getId()
        );

        return handoffMapper.toDetail(handoff);
    }

    public void delete(Long handoffId) {

        Long currentUserId = currentUserService.getCurrentUserId();

        Handoff handoff = handoffRepository.findDetailById(handoffId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Handoff no encontrado"));

        authorizationService.requireAccess(
                currentUserId,
                handoff.getCareSubject().getId()
        );

        handoffRepository.delete(handoff);
    }
}
