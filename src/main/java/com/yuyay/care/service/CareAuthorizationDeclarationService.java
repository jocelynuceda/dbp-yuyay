package com.yuyay.care.service;

import com.yuyay.care.dto.CareAuthorizationDeclarationResponse;
import com.yuyay.care.dto.CareAuthorizationPolicyResponse;
import com.yuyay.care.dto.CareAuthorizationPolicyResponse.Option;
import com.yuyay.care.dto.CareAuthorizationUpdateRequest;
import com.yuyay.care.entity.CareAuthorizationDeclaration;
import com.yuyay.care.entity.CareAuthorizationType;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.mapper.CareAuthorizationDeclarationMapper;
import com.yuyay.care.repository.CareAuthorizationDeclarationRepository;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CareAuthorizationDeclarationService {
    private final CareAuthorizationDeclarationRepository declarationRepo;
    private final UserRepository userRepo;
    private final CareAuthorizationDeclarationMapper mapper;
    private final AuthorizationService authorizationService;
    private final CurrentUserService currentUserService;

    public CareAuthorizationPolicyResponse getPolicy() {
        return CareAuthorizationPolicy.CURRENT;
    }

    @Transactional(readOnly = true)
    public List<CareAuthorizationDeclarationResponse> history(Long subjectId) {
        Long actorId = currentUserService.getCurrentUserId();
        authorizationService.requireRole(actorId, subjectId, CareRole.PRINCIPAL);
        return declarationRepo.findByCareSubjectIdOrderByIdDesc(subjectId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional
    public CareAuthorizationDeclarationResponse declare(Long subjectId, CareAuthorizationUpdateRequest request) {
        Long actorId = currentUserService.getCurrentUserId();
        CareSubject subject = authorizationService.requireRoleForUpdate(actorId, subjectId, CareRole.PRINCIPAL);
        Option option = CareAuthorizationPolicy.validate(request.declaration());
        CareAuthorizationDeclaration latest = declarationRepo.findFirstByCareSubjectIdOrderByIdDesc(subjectId)
                .orElse(null);
        if (latest != null && latest.getDeclaredBy().getId().equals(actorId)
                && latest.getType() == option.type() && latest.getTextVersion().equals(request.textVersion())) {
            return mapper.toResponse(latest);
        }
        Long latestId = latest == null ? null : latest.getId();
        if (!Objects.equals(latestId, request.previousDeclarationId())) {
            throw new InvalidOperationException("La declaración de esta persona cambió. Consulta el historial e inténtalo de nuevo");
        }
        User actor = userRepo.getReferenceById(actorId);
        return mapper.toResponse(append(subject, actor, option, null, null));
    }

    /**
     * Registra la declaración {@code SELF} del titular que acaba de recibir su perfil: su propia voz
     * sobre su dato, con el texto y la versión vigentes. No reescribe el historial anterior.
     */
    CareAuthorizationDeclaration appendSelfDeclaration(CareSubject subject, User actor) {
        Option self = CareAuthorizationPolicy.CURRENT.options().stream()
                .filter(option -> option.type() == CareAuthorizationType.SELF)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("El catálogo no incluye la declaración SELF"));
        return append(subject, actor, self, null, null);
    }

    Optional<CareSubject> replayCreation(Long actorId, String requestId, String payloadHash) {
        return declarationRepo.findByDeclaredByIdAndCreationRequestId(actorId, requestId).map(declaration -> {
            if (!payloadHash.equals(declaration.getCreationPayloadHash())) {
                throw new InvalidOperationException("El identificador de esta solicitud ya se usó con otros datos");
            }
            return declaration.getCareSubject();
        });
    }

    CareAuthorizationDeclaration append(CareSubject subject, User actor, Option option,
                                        String creationRequestId, String creationPayloadHash) {
        return declarationRepo.save(CareAuthorizationDeclaration.builder()
                .careSubject(subject)
                .declaredBy(actor)
                .type(option.type())
                .scope(option.scope())
                .textVersion(CareAuthorizationPolicy.CURRENT.textVersion())
                .text(CareAuthorizationPolicy.snapshot(option))
                .declaredAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .creationRequestId(creationRequestId)
                .creationPayloadHash(creationPayloadHash)
                .build());
    }
}
