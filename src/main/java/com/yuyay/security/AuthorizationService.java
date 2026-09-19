package com.yuyay.security;

import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.entity.Delegation;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.DelegationRepository;
import com.yuyay.exception.DelegationExpiredException;
import com.yuyay.exception.ForbiddenCareSubjectAccessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthorizationService {
    private final CareRelationshipRepository careRelationships;
    private final DelegationRepository delegations;

    public void requireAccess(Long userId, Long careSubjectId) {
        if (!hasAccess(userId, careSubjectId)) {
            throw new ForbiddenCareSubjectAccessException("No tienes acceso a esta persona");
        }
    }

    public boolean hasAccess(Long userId, Long careSubjectId) {
        return careRelationships.existsByUserIdAndCareSubjectIdAndStatus(userId, careSubjectId, CareStatus.ACTIVE);
    }

    public void requireRole(Long userId, Long careSubjectId, CareRole role) {
        if (!careRelationships.existsByUserIdAndCareSubjectIdAndStatusAndRole(userId, careSubjectId, CareStatus.ACTIVE, role)) {
            throw new ForbiddenCareSubjectAccessException("Se requiere rol " + role + " sobre esta persona");
        }
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
