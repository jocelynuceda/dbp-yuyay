package com.yuyay.consultation.service;

import com.yuyay.consultation.dto.*;
import com.yuyay.consultation.entity.*;
import com.yuyay.consultation.mapper.HandoffMapper;
import com.yuyay.consultation.repository.HandoffRepository;
import com.yuyay.exception.InvalidHandoffException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.health.entity.HealthEntry;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import com.yuyay.security.TokenHasher;

@Service
@RequiredArgsConstructor
@Transactional
public class ConsultationConfirmationService {
    private final EntityManager entityManager;
    private final UserRepository users;
    private final CareSubjectRepository subjects;
    private final HandoffRepository handoffs;
    private final HealthEntryVersionRepository versions;
    private final HandoffMapper mapper;
    private final AuthorizationService authorization;
    private final CurrentUserService currentUser;

    public HandoffDetailDTO confirm(Long consultationId, ConfirmPreparationDTO request) {
        Long userId = currentUser.getCurrentUserId();
        // El bloqueo de cuenta serializa la clave incluso cuando a煤n no existe el comprobante.
        var user = users.findForUpdateById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        var consultation = entityManager.find(Consultation.class, consultationId, LockModeType.PESSIMISTIC_WRITE);
        if (consultation == null) throw new ResourceNotFoundException("Consulta no encontrada");
        Long subjectId = consultation.getCareSubject().getId();
        authorization.requireAccess(userId, subjectId);
        subjects.findForUpdateById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Persona no encontrada"));
        authorization.requireAccess(userId, subjectId);

        // Longitudes y separadores expl铆citos evitan ambig眉edad entre campos.
        String fingerprint = TokenHasher.sha256(consultationId + "|" + request.preparationToken()
                + "|" + encode(request.reason()) + encode(request.questions()) + request.entries().toString());
        var previous = handoffs.findByCreatedByIdAndConfirmationRequestId(userId, request.requestId().toString());
        if (previous.isPresent()) {
            if (!fingerprint.equals(previous.get().getConfirmationFingerprint())) {
                throw new PreparationConflictException("La clave ya fue utilizada con otro contenido");
            }
            return mapper.toDetail(previous.get());
        }
        if (!ConsultationPreparationService.snapshot(consultation).equals(request.preparationToken())) {
            throw new PreparationConflictException("La cita cambi贸. Revisa nuevamente la preparaci贸n");
        }
        var uniqueIds = new HashSet<Long>();
        for (var selection : request.entries()) {
            if (!uniqueIds.add(selection.entryId())) throw new InvalidHandoffException("Registros repetidos");
        }
        // Orden de bloqueo estable para solicitudes con selecciones en distinto orden.
        for (Long entryId : uniqueIds.stream().sorted().toList()) {
            var entry = entityManager.find(HealthEntry.class, entryId, LockModeType.PESSIMISTIC_WRITE);
            if (entry == null || !entry.getCareSubject().getId().equals(subjectId)) {
                throw new InvalidHandoffException("La selecci贸n contiene registros no disponibles");
            }
            entityManager.refresh(entry, LockModeType.PESSIMISTIC_WRITE);
            if (entry.isDeleted()) throw new PreparationConflictException("Un registro fue eliminado");
        }
        var handoff = Handoff.builder().careSubject(consultation.getCareSubject()).createdBy(user)
                .reason(request.reason()).questions(request.questions())
                .confirmationRequestId(request.requestId().toString()).confirmationFingerprint(fingerprint).build();
        int position = 0;
        for (var selection : request.entries()) {
            var version = versions.findFirstByHealthEntryIdOrderByVersionNumberDesc(selection.entryId())
                    .orElseThrow(() -> new PreparationConflictException("Un registro no tiene versiones"));
            if (!version.getId().equals(selection.versionId())) {
                throw new PreparationConflictException("Un registro cambi贸. Revisa nuevamente la preparaci贸n");
            }
            handoff.getItems().add(HandoffItem.builder().handoff(handoff)
                    .healthEntryVersion(version).position(position++).build());
        }
        authorization.requireAccess(userId, subjectId);
        var saved = handoffs.saveAndFlush(handoff);
        // Devuelve tambi閚 en el primer intento los valores normalizados por la base.
        entityManager.refresh(saved);
        return mapper.toDetail(saved);
    }

    private static String encode(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }
}
