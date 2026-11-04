package com.yuyay.consultation.service;

import com.yuyay.care.entity.Delegation;
import com.yuyay.consultation.dto.DelegateConsultationResponse;
import com.yuyay.consultation.repository.ConsultationRepository;
import com.yuyay.exception.ForbiddenCareSubjectAccessException;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.security.DelegatePrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DelegateConsultationService {

    private final ConsultationRepository consultationRepository;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;

    public List<DelegateConsultationResponse> list() {
        DelegatePrincipal principal = currentUserService.getDelegatePrincipal();
        Delegation delegation = authorizationService.requireDelegateAccess(
                principal.delegationId(), principal.careSubjectId(), null);
        if (!delegation.isIncludeConsultations()) {
            throw new ForbiddenCareSubjectAccessException("La delegación no incluye las citas");
        }
        return consultationRepository.findByCareSubjectIdOrderByDateDesc(principal.careSubjectId()).stream()
                .map(c -> new DelegateConsultationResponse(c.getId(), c.getDate(), c.getTime(), c.getReason()))
                .toList();
    }
}
