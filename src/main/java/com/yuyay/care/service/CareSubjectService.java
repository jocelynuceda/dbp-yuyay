package com.yuyay.care.service;

import com.yuyay.care.dto.CareSubjectCreateRequest;
import com.yuyay.care.dto.CareSubjectResponse;
import com.yuyay.care.dto.CareSubjectUpdateRequest;
import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.exception.CareSubjectNotFoundException;
import com.yuyay.care.mapper.CareSubjectMapper;
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
public class CareSubjectService {

    private final CareSubjectRepository subjectRepo;
    private final CareRelationshipRepository relationshipRepo;
    private final UserRepository userRepo;
    private final CareSubjectMapper mapper;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;

    // ---------- CREATE ----------

    public CareSubjectResponse create(CareSubjectCreateRequest req) {
        Long meId = currentUserService.getCurrentUserId();
        User me = loadUser(meId);

        CareSubject subject = mapper.toEntity(req);
        subjectRepo.save(subject);

        // El creador queda automáticamente como PRINCIPAL ACTIVE
        CareRelationship ownerRelationship = CareRelationship.builder()
                .user(me)
                .careSubject(subject)
                .role(CareRole.PRINCIPAL)
                .status(CareStatus.ACTIVE)
                .acceptedAt(Instant.now())
                .build();
        relationshipRepo.save(ownerRelationship);

        return mapper.toResponse(subject, CareRole.PRINCIPAL);
    }

    // ---------- READ ----------

    @Transactional(readOnly = true)
    public List<CareSubjectResponse> listMine() {
        Long meId = currentUserService.getCurrentUserId();

        return subjectRepo.findAllAccessibleBy(meId).stream()
                .map(cs -> {
                    CareRole role = relationshipRepo
                            .findByUserIdAndCareSubjectId(meId, cs.getId())
                            .map(CareRelationship::getRole)
                            .orElse(null);
                    return mapper.toResponse(cs, role);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public CareSubjectResponse get(Long subjectId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);

        CareSubject cs = loadSubject(subjectId);

        CareRole role = relationshipRepo
                .findByUserIdAndCareSubjectId(meId, subjectId)
                .map(CareRelationship::getRole)
                .orElse(null);

        return mapper.toResponse(cs, role);
    }

    // ---------- UPDATE ----------

    public CareSubjectResponse update(Long subjectId, CareSubjectUpdateRequest req) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);

        CareSubject cs = loadSubject(subjectId);

        cs.setName(req.name());
        cs.setBirthDate(req.birthDate());
        cs.setNotes(req.notes());

        CareRole role = relationshipRepo
                .findByUserIdAndCareSubjectId(meId, subjectId)
                .map(CareRelationship::getRole)
                .orElse(null);

        return mapper.toResponse(cs, role);
    }

    // ---------- DELETE (solo PRINCIPAL) ----------

    public void delete(Long subjectId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireRole(meId, subjectId, CareRole.PRINCIPAL);

        CareSubject cs = loadSubject(subjectId);
        subjectRepo.delete(cs);
    }

    // ---------- HELPERS ----------

    private User loadUser(Long id) {
        return userRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private CareSubject loadSubject(Long id) {
        return subjectRepo.findById(id)
                .orElseThrow(() -> new CareSubjectNotFoundException(id));
    }
}