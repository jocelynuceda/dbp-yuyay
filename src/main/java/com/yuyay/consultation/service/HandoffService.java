package com.yuyay.consultation.service;

import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.consultation.dto.CreateHandoffDTO;
import com.yuyay.consultation.dto.HandoffDetailDTO;
import com.yuyay.consultation.dto.HandoffResponseDTO;
import com.yuyay.consultation.entity.Handoff;
import com.yuyay.consultation.entity.HandoffItem;
import com.yuyay.consultation.mapper.HandoffMapper;
import com.yuyay.consultation.repository.HandoffRepository;
import com.yuyay.exception.InvalidHandoffException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.health.entity.HealthEntry;
import com.yuyay.health.entity.HealthEntryVersion;
import com.yuyay.health.repository.HealthEntryRepository;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
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
    private final CareSubjectRepository careSubjectRepository;
    private final HealthEntryRepository healthEntryRepository;
    private final HealthEntryVersionRepository healthEntryVersionRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;
    private final HandoffMapper handoffMapper;

    public HandoffDetailDTO create(Long careSubjectId, CreateHandoffDTO request) {

        Long currentUserId = currentUserService.getCurrentUserId();

        authorizationService.requireAccess(currentUserId, careSubjectId);

        CareSubject careSubject = careSubjectRepository.findById(careSubjectId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Persona cuidada no encontrada"));

        Set<Long> uniqueIds = new HashSet<>(request.healthEntryIds());

        if (uniqueIds.size() != request.healthEntryIds().size()) {
            throw new InvalidHandoffException(
                    "No se pueden incluir registros de salud repetidos"
            );
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

        User currentUser = userRepository.getReferenceById(currentUserId);

        Handoff handoff = Handoff.builder()
                .careSubject(careSubject)
                .createdBy(currentUser)
                .reason(request.reason())
                .questions(request.questions())
                .build();

        for (HealthEntry entry : entries) {

            HealthEntryVersion latestVersion =
                    healthEntryVersionRepository
                            .findFirstByHealthEntryIdOrderByVersionNumberDesc(
                                    entry.getId()
                            )
                            .orElseThrow(() ->
                                    new InvalidHandoffException(
                                            "El registro de salud "
                                                    + entry.getId()
                                                    + " no tiene versiones"
                                    ));

            HandoffItem item = HandoffItem.builder()
                    .handoff(handoff)
                    .healthEntryVersion(latestVersion)
                    .build();

            handoff.getItems().add(item);
        }

        Handoff saved = handoffRepository.save(handoff);

        return handoffMapper.toDetail(saved);
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