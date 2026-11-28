package com.yuyay.security;

import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.entity.Delegation;
import com.yuyay.care.exception.CareSubjectNotFoundException;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.care.repository.DelegationRepository;
import com.yuyay.exception.DelegationExpiredException;
import com.yuyay.exception.ForbiddenCareSubjectAccessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthorizationService {
    private final CareRelationshipRepository careRelationships;
    private final CareSubjectRepository careSubjects;
    private final DelegationRepository delegations;

    public void requireAccess(Long userId, Long careSubjectId) {
        if (!hasAccess(userId, careSubjectId)) {
            throw new ForbiddenCareSubjectAccessException("No tienes acceso a esta persona");
        }
        protectRegistrationProfileWrite(userId, careSubjectId, null);
    }

    public boolean hasAccess(Long userId, Long careSubjectId) {
        return careRelationships.existsByUserIdAndCareSubjectIdAndStatus(userId, careSubjectId, CareStatus.ACTIVE);
    }

    public void requireRole(Long userId, Long careSubjectId, CareRole role) {
        if (!careRelationships.existsByUserIdAndCareSubjectIdAndStatusAndRole(userId, careSubjectId, CareStatus.ACTIVE, role)) {
            throw new ForbiddenCareSubjectAccessException("Se requiere rol " + role + " sobre esta persona");
        }
        protectRegistrationProfileWrite(userId, careSubjectId, role);
    }


    private void protectRegistrationProfileWrite(Long userId, Long subjectId, CareRole requiredRole) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !careSubjects.existsByIdAndRegistrationProfileTrue(subjectId)) return;
        CareSubject subject = careSubjects.findForUpdateById(subjectId)
                .orElseThrow(() -> new CareSubjectNotFoundException(subjectId));
        // Un canje pudo revocar la relación mientras esta escritura esperaba el bloqueo.
        boolean allowed = requiredRole == null
                ? hasAccess(userId, subjectId)
                : careRelationships.existsByUserIdAndCareSubjectIdAndStatusAndRole(userId, subjectId, CareStatus.ACTIVE, requiredRole);
        if (!allowed) throw new ForbiddenCareSubjectAccessException("El acceso a esta persona cambió.");
        subject.setRegistrationProfileUsed(true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CareSubject requireAccessForUpdate(Long userId, Long careSubjectId) {
        requireAccess(userId, careSubjectId);
        CareSubject subject = careSubjects.findForUpdateById(careSubjectId)
                .orElseThrow(() -> new CareSubjectNotFoundException(careSubjectId));
        // Una revocación puede haberse confirmado mientras esperábamos el bloqueo.
        requireAccess(userId, careSubjectId);
        return subject;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CareSubject requireRoleForUpdate(Long userId, Long careSubjectId, CareRole role) {
        requireRole(userId, careSubjectId, role);
        CareSubject subject = careSubjects.findForUpdateById(careSubjectId)
                .orElseThrow(() -> new CareSubjectNotFoundException(careSubjectId));
        // Una revocación puede haberse confirmado mientras esperábamos el bloqueo.
        requireRole(userId, careSubjectId, role);
        return subject;
    }

    public Delegation requireDelegateAccess(Long delegationId, Long careSubjectId, String categoryCode) {
        Delegation delegation = delegations.findWithCategoriesById(delegationId)
                .orElseThrow(() -> new DelegationExpiredException("La delegación no existe"));
        if (!delegation.isValidNow() || !delegation.getCareSubject().getId().equals(careSubjectId)) {
            throw new DelegationExpiredException("La delegación fue revocada o ya no está vigente");
        }
        if (categoryCode != null && delegation.getCategories().stream().noneMatch(c -> c.getCode().equals(categoryCode))) {
            throw new ForbiddenCareSubjectAccessException("La delegación no incluye la categoría " + categoryCode);
        }
        return delegation;
    }
}
