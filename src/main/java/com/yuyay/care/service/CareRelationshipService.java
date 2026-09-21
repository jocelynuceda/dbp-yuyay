package com.yuyay.care.service;

import com.yuyay.care.dto.CareRelationshipInviteRequest;
import com.yuyay.care.dto.CareRelationshipResponse;
import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.exception.CareRelationshipNotFoundException;
import com.yuyay.care.exception.CareSubjectNotFoundException;
import com.yuyay.care.mapper.CareRelationshipMapper;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.event.CaregiverInvitedEvent;
import com.yuyay.exception.DuplicateCareRelationshipException;
import com.yuyay.exception.ForbiddenCareSubjectAccessException;
import com.yuyay.exception.InvalidCareRelationshipException;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CareRelationshipService {

    private final CareRelationshipRepository relationshipRepo;
    private final CareSubjectRepository subjectRepo;
    private final UserRepository userRepo;
    private final CareRelationshipMapper mapper;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;
    private final ApplicationEventPublisher eventPublisher;

    public CareRelationshipResponse invite(Long subjectId, CareRelationshipInviteRequest req) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireRole(meId, subjectId, CareRole.PRINCIPAL);

        CareSubject cs = loadSubject(subjectId);
        User invitee = userRepo.findByEmailIgnoreCase(req.email())
                .orElseThrow(() -> new ResourceNotFoundException("No existe una cuenta con el email " + req.email()));

        if (invitee.getId().equals(meId)) {
            throw new InvalidCareRelationshipException("No puedes invitarte a ti mismo");
        }
        if (relationshipRepo.findByUserIdAndCareSubjectId(invitee.getId(), subjectId).isPresent()) {
            throw new DuplicateCareRelationshipException("Ese usuario ya está vinculado a esta persona");
        }

        CareRelationship rel = relationshipRepo.save(CareRelationship.builder()
                .user(invitee)
                .careSubject(cs)
                .role(req.role())
                .relationshipLabel(req.relationshipLabel())
                .status(CareStatus.PENDING)
                .build());

        eventPublisher.publishEvent(new CaregiverInvitedEvent(rel.getId()));
        return mapper.toResponse(rel);
    }

    @Transactional(readOnly = true)
    public List<CareRelationshipResponse> listBySubject(Long subjectId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);

        return relationshipRepo.findByCareSubjectId(subjectId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CareRelationshipResponse> listMyPending() {
        Long meId = currentUserService.getCurrentUserId();

        return relationshipRepo.findByUserIdAndStatus(meId, CareStatus.PENDING).stream()
                .map(mapper::toResponse)
                .toList();
    }

    public CareRelationshipResponse accept(Long relationshipId) {
        Long meId = currentUserService.getCurrentUserId();
        CareRelationship rel = loadRelationship(relationshipId);

        if (!rel.getUser().getId().equals(meId)) {
            throw new ForbiddenCareSubjectAccessException("Esta invitación no es tuya");
        }
        if (rel.getStatus() != CareStatus.PENDING) {
            throw new InvalidCareRelationshipException("La invitación no está pendiente");
        }

        rel.setStatus(CareStatus.ACTIVE);
        rel.setAcceptedAt(Instant.now());
        return mapper.toResponse(rel);
    }

    public void revoke(Long subjectId, Long relationshipId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireRole(meId, subjectId, CareRole.PRINCIPAL);

        CareRelationship rel = loadRelationship(relationshipId);
        if (!rel.getCareSubject().getId().equals(subjectId)) {
            throw new CareRelationshipNotFoundException(relationshipId);
        }
        if (rel.getStatus() == CareStatus.REVOKED) {
            throw new InvalidOperationException("La relación ya fue revocada");
        }
        if (rel.getRole() == CareRole.PRINCIPAL
                && relationshipRepo.countByCareSubjectIdAndStatusAndRole(subjectId, CareStatus.ACTIVE, CareRole.PRINCIPAL) <= 1) {
            throw new InvalidOperationException("No se puede revocar al único PRINCIPAL de esta persona");
        }

        rel.setStatus(CareStatus.REVOKED);
        rel.setRevokedAt(Instant.now());
    }

    private CareSubject loadSubject(Long id) {
        return subjectRepo.findById(id)
                .orElseThrow(() -> new CareSubjectNotFoundException(id));
    }

    private CareRelationship loadRelationship(Long id) {
        return relationshipRepo.findById(id)
                .orElseThrow(() -> new CareRelationshipNotFoundException(id));
    }
}
