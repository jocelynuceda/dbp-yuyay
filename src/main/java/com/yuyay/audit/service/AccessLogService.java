package com.yuyay.audit.service;

import com.yuyay.audit.dto.AccessLogResponseDTO;
import com.yuyay.audit.entity.AccessLog;
import com.yuyay.audit.repository.AccessLogRepository;
import com.yuyay.care.entity.CareRole;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessLogService {

    private final AccessLogRepository accessLogRepository;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;

    public List<AccessLogResponseDTO> list(Long careSubjectId) {

        Long currentUserId = currentUserService.getCurrentUserId();

        authorizationService.requireRole(
                currentUserId,
                careSubjectId,
                CareRole.PRINCIPAL
        );

        return accessLogRepository
                .findByCareSubjectIdOrderByOccurredAtDesc(careSubjectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private AccessLogResponseDTO toResponse(AccessLog accessLog) {

        return new AccessLogResponseDTO(
                accessLog.getId(),
                accessLog.getCareSubject().getId(),
                accessLog.getUser() == null
                        ? null
                        : accessLog.getUser().getId(),
                accessLog.getDelegation() == null
                        ? null
                        : accessLog.getDelegation().getId(),
                accessLog.getAction(),
                accessLog.getTargetType(),
                accessLog.getTargetId(),
                accessLog.getVisitorName(),
                accessLog.getVisitorRole(),
                accessLog.getOccurredAt()
        );
    }
}