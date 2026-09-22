package com.yuyay.consultation.service;

import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.consultation.dto.ConsultationDetailDTO;
import com.yuyay.consultation.dto.ConsultationResponseDTO;
import com.yuyay.consultation.dto.CreateConsultationDTO;
import com.yuyay.consultation.dto.UpdateConsultationDTO;
import com.yuyay.consultation.entity.Consultation;
import com.yuyay.consultation.mapper.ConsultationMapper;
import com.yuyay.consultation.repository.ConsultationRepository;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ConsultationService {

    private final ConsultationRepository consultationRepository;
    private final CareSubjectRepository careSubjectRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;
    private final ConsultationMapper consultationMapper;

    public ConsultationDetailDTO create(
            Long careSubjectId,
            CreateConsultationDTO request
    ) {
        Long currentUserId = currentUserService.getCurrentUserId();

        // Solo el PRINCIPAL puede crear consultas
        authorizationService.requireRole(
                currentUserId,
                careSubjectId,
                CareRole.PRINCIPAL
        );

        CareSubject careSubject = careSubjectRepository.findById(careSubjectId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Persona cuidada no encontrada"));

        User currentUser = userRepository.getReferenceById(currentUserId);

        Consultation consultation = Consultation.builder()
                .careSubject(careSubject)
                .createdBy(currentUser)
                .date(request.date())
                .reason(request.reason())
                .notes(request.notes())
                .build();

        Consultation saved = consultationRepository.save(consultation);

        return consultationMapper.toDetail(saved);
    }

    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> list(Long careSubjectId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        authorizationService.requireAccess(currentUserId, careSubjectId);

        return consultationRepository
                .findByCareSubjectIdOrderByDateDesc(careSubjectId)
                .stream()
                .map(consultationMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConsultationDetailDTO get(Long consultationId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Consulta no encontrada"));

        authorizationService.requireAccess(
                currentUserId,
                consultation.getCareSubject().getId()
        );

        return consultationMapper.toDetail(consultation);
    }

    public ConsultationDetailDTO update(
            Long consultationId,
            UpdateConsultationDTO request
    ) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Consulta no encontrada"));

        Long careSubjectId = consultation.getCareSubject().getId();

        // Solo el PRINCIPAL puede editar
        authorizationService.requireRole(
                currentUserId,
                careSubjectId,
                CareRole.PRINCIPAL
        );

        if (request.date() != null) {
            consultation.setDate(request.date());
        }
        if (request.reason() != null && !request.reason().isBlank()) {
            consultation.setReason(request.reason());
        }
        if (request.notes() != null) {
            consultation.setNotes(request.notes());
        }

        return consultationMapper.toDetail(consultation);
    }

    public void delete(Long consultationId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Consulta no encontrada"));

        Long careSubjectId = consultation.getCareSubject().getId();

        // Solo el PRINCIPAL puede eliminar
        authorizationService.requireRole(
                currentUserId,
                careSubjectId,
                CareRole.PRINCIPAL
        );

        consultationRepository.delete(consultation);
    }


    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> changes(
            Long careSubjectId,
            Long sinceConsultationId
    ) {
        Long currentUserId = currentUserService.getCurrentUserId();

        authorizationService.requireAccess(currentUserId, careSubjectId);

        return consultationRepository
                .findByCareSubjectIdAndIdGreaterThanOrderByIdAsc(
                        careSubjectId,
                        sinceConsultationId
                )
                .stream()
                .map(consultationMapper::toResponse)
                .toList();
    }
}