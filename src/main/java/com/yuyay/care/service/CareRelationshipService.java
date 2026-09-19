package com.yuyay.care.service;

import com.yuyay.care.dto.CareRelationshipInviteRequest;
import com.yuyay.care.dto.CareRelationshipResponse;
import com.yuyay.care.entity.*;
import com.yuyay.care.exception.CareRelationshipNotFoundException;
import com.yuyay.care.exception.CareSubjectNotFoundException;
import com.yuyay.care.exception.DuplicateCareRelationshipException;
import com.yuyay.care.mapper.CareRelationshipMapper;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import com.yuyay.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
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

    // ---------- INVITE (solo PRINCIPAL) ----------

    public CareRelationshipResponse invite(Long subjectId, CareRelationshipInviteRequest req) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireRole(meId, subjectId, CareRole.PRINCIPAL);

        CareSubject cs = loadSubject(subjectId);

        // DESPUÉS:
        User invitee = userRepo.findByEmailIgnoreCase(req.email())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + req.email()));
        if (relationshipRepo.findByUserIdAndCareSubjectId(invitee.getId(), subjectId).isPresent()) {
            throw new DuplicateCareRelationshipException(invitee.getId(), subjectId);
        }

        CareRelationship rel = CareRelationship.builder()
                .user(invitee)
                .careSubject(cs)
                .role(req.role())
                .relationshipLabel(req.relationshipLabel())
                .status(CareStatus.PENDING)
                .build();
        relationshipRepo.save(rel);

        return mapper.toResponse(rel);
    }

    // ---------- LIST ----------

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

    // ---------- ACCEPT (solo el invitado) ----------

    public CareRelationshipResponse accept(Long relationshipId) {
        Long meId = currentUserService.getCurrentUserId();

        CareRelationship rel = loadRelationship(relationshipId);

        if (!rel.getUser().getId().equals(meId)) {
            throw new com.yuyay.exception.ForbiddenCareSubjectAccessException(
                    "Esta invitación no es tuya");
        }
        if (rel.getStatus() != CareStatus.PENDING) {
            throw new IllegalStateException("La invitación no está pendiente");
        }

        rel.setStatus(CareStatus.ACTIVE);
        rel.setAcceptedAt(Instant.now());

        return mapper.toResponse(rel);
    }

    // ---------- REVOKE (solo PRINCIPAL) ----------

    public void revoke(Long subjectId, Long relationshipId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireRole(meId, subjectId, CareRole.PRINCIPAL);

        CareRelationship rel = loadRelationship(relationshipId);

        if (!rel.getCareSubject().getId().equals(subjectId)) {
            throw new CareRelationshipNotFoundException(relationshipId);
        }
        if (rel.getRole() == CareRole.PRINCIPAL) {
            throw new IllegalStateException("No se puede revocar al PRINCIPAL");
        }

        rel.setStatus(CareStatus.REVOKED);
        rel.setRevokedAt(Instant.now());
    }

    // ---------- HELPERS ----------

    private CareSubject loadSubject(Long id) {
        return subjectRepo.findById(id)
                .orElseThrow(() -> new CareSubjectNotFoundException(id));
    }

    private CareRelationship loadRelationship(Long id) {
        return relationshipRepo.findById(id)
                .orElseThrow(() -> new CareRelationshipNotFoundException(id));
    }
}