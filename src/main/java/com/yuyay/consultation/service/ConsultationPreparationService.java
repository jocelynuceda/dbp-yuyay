package com.yuyay.consultation.service;

import com.yuyay.consultation.dto.ConsultationPreparationDTO;
import com.yuyay.consultation.entity.Consultation;
import com.yuyay.consultation.repository.ConsultationRepository;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.health.mapper.HealthEntryVersionMapper;
import com.yuyay.health.repository.HealthEntryRepository;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsultationPreparationService {
    private final ConsultationRepository consultations;
    private final HealthEntryRepository entries;
    private final HealthEntryVersionRepository versions;
    private final HealthEntryVersionMapper mapper;
    private final CurrentUserService currentUser;
    private final AuthorizationService authorization;

    public ConsultationPreparationDTO prepare(Long id) {
        Consultation consultation = consultations.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Consulta no encontrada"));
        Long subjectId = consultation.getCareSubject().getId();
        authorization.requireAccess(currentUser.getCurrentUserId(), subjectId);
        var ids = entries.findByCareSubjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(subjectId)
                .stream().map(e -> e.getId()).toList();
        var latest = ids.isEmpty() ? java.util.List.<com.yuyay.health.entity.HealthEntryVersion>of()
                : versions.findLatestByHealthEntryIdIn(ids);
        var grouped = latest.stream().sorted(Comparator.comparing(v -> v.getHealthEntry().getId()))
                .collect(Collectors.groupingBy(v -> v.getHealthEntry().getCategory().getCode(),
                        TreeMap::new, Collectors.mapping(v -> new ConsultationPreparationDTO.Entry(
                                v.getHealthEntry().getId(), v.getId(), mapper.toResponse(v)), Collectors.toList())));
        return new ConsultationPreparationDTO(id, subjectId, consultation.getReason(), snapshot(consultation),
                grouped.entrySet().stream().map(e -> new ConsultationPreparationDTO.Category(e.getKey(), e.getValue())).toList());
    }

    // Incluye los campos revisables de la cita, sin depender del reloj ni de orden de consultas.
    public static String snapshot(Consultation consultation) {
        return TokenHasher.sha256(java.util.Arrays.asList(consultation.getId(), consultation.getDate(),
                consultation.getTime(), consultation.getReason(), consultation.getNotes()).toString());
    }
}
