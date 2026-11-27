package com.yuyay.care.service;

import com.yuyay.care.dto.IdentityCheckRequest;
import com.yuyay.care.dto.IdentityCheckResponse;
import com.yuyay.care.dto.IdentityCheckResult;
import com.yuyay.care.entity.CareAuthorizationType;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.exception.CareSubjectNotFoundException;
import com.yuyay.care.repository.CareAuthorizationDeclarationRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.identity.IdentityDirectory;
import com.yuyay.identity.IdentityLookup;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Contrasta el nombre de un perfil con el que figura en un DNI.
 *
 * <p>No acredita identidad y no verifica la edad: el proveedor disponible no devuelve fecha de
 * nacimiento. Reduce el perfil con nombre inventado y el error de tipeo antes de que ese perfil se
 * entregue a su titular. Ver {@code docs/modelo-autocuidado-y-recordatorios.md} §3.5.
 *
 * <p>El DNI no se guarda ni se registra: transita hacia el directorio y se descarta.
 */
@Service
@RequiredArgsConstructor
public class CareSubjectIdentityCheckService {

    private final CareSubjectRepository careSubjects;
    private final CareAuthorizationDeclarationRepository declarations;
    private final AuthorizationService authorizationService;
    private final CurrentUserService currentUserService;
    private final IdentityDirectory identityDirectory;

    @Transactional(readOnly = true)
    public IdentityCheckResponse check(Long careSubjectId, IdentityCheckRequest request) {
        Long actorId = currentUserService.getCurrentUserId();
        // El permiso se resuelve por la relación activa, nunca por lo que venga en el cuerpo.
        authorizationService.requireRole(actorId, careSubjectId, CareRole.PRINCIPAL);
        requireNotFictitious(careSubjectId);
        CareSubject subject = careSubjects.findById(careSubjectId)
                .orElseThrow(() -> new CareSubjectNotFoundException(careSubjectId));

        IdentityLookup lookup = identityDirectory.findByDni(request.dni());
        return new IdentityCheckResponse(
                resultOf(lookup, subject.getName()),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** Un perfil declarado ficticio no se contrasta: gastaría una consulta real sobre datos inventados. */
    private void requireNotFictitious(Long careSubjectId) {
        declarations.findFirstByCareSubjectIdOrderByIdDesc(careSubjectId)
                .filter(declaration -> declaration.getType() == CareAuthorizationType.FICTITIOUS)
                .ifPresent(declaration -> {
                    throw new InvalidOperationException(
                            "Esta persona está declarada como ficticia: no corresponde contrastarla con un documento real");
                });
    }

    private IdentityCheckResult resultOf(IdentityLookup lookup, String declaredName) {
        return switch (lookup) {
            case IdentityLookup.Found found -> found.person().matchesName(declaredName)
                    ? IdentityCheckResult.MATCH
                    : IdentityCheckResult.MISMATCH;
            case IdentityLookup.NotFound ignored -> IdentityCheckResult.NOT_FOUND;
            case IdentityLookup.Unavailable ignored -> IdentityCheckResult.UNAVAILABLE;
        };
    }
}
