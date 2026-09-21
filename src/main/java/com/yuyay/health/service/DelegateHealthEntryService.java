package com.yuyay.health.service;

import com.yuyay.audit.entity.AccessAction;
import com.yuyay.care.entity.Delegation;
import com.yuyay.event.AccessRecordedEvent;
import com.yuyay.exception.ForbiddenCareSubjectAccessException;
import com.yuyay.health.dto.HealthEntryResponse;
import com.yuyay.health.entity.HealthCategory;
import com.yuyay.health.entity.HealthEntry;
import com.yuyay.health.entity.HealthEntryVersion;
import com.yuyay.health.exception.HealthEntryNotFoundException;
import com.yuyay.health.mapper.HealthEntryMapper;
import com.yuyay.health.mapper.HealthEntryVersionMapper;
import com.yuyay.health.repository.HealthEntryRepository;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.security.DelegatePrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DelegateHealthEntryService {

    private final HealthEntryRepository entryRepo;
    private final HealthEntryVersionRepository versionRepo;
    private final HealthEntryMapper entryMapper;
    private final HealthEntryVersionMapper versionMapper;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;
    private final ApplicationEventPublisher eventPublisher;

    public List<HealthEntryResponse> list(String categoryCode) {
        DelegatePrincipal principal = currentUserService.getDelegatePrincipal();
        Delegation delegation = authorizationService.requireDelegateAccess(
                principal.delegationId(), principal.careSubjectId(), categoryCode);

        Set<String> allowed = allowedCodes(delegation);
        Set<String> codes = categoryCode == null || categoryCode.isBlank() ? allowed : Set.of(categoryCode);

        return entryRepo.findAccessibleByDelegation(principal.careSubjectId(), codes).stream()
                .map(this::toResponse)
                .toList();
    }

    public HealthEntryResponse get(Long entryId) {
        DelegatePrincipal principal = currentUserService.getDelegatePrincipal();
        Delegation delegation = authorizationService.requireDelegateAccess(
                principal.delegationId(), principal.careSubjectId(), null);

        HealthEntry entry = entryRepo.findWithCategoryById(entryId)
                .filter(e -> e.getCareSubject().getId().equals(principal.careSubjectId()))
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new HealthEntryNotFoundException(entryId));

        if (!allowedCodes(delegation).contains(entry.getCategory().getCode())) {
            throw new ForbiddenCareSubjectAccessException(
                    "La delegación no incluye la categoría " + entry.getCategory().getCode());
        }

        eventPublisher.publishEvent(AccessRecordedEvent.byDelegation(
                principal.careSubjectId(), delegation.getId(), AccessAction.VIEW, "HEALTH_ENTRY", entry.getId()));
        return toResponse(entry);
    }

    private HealthEntryResponse toResponse(HealthEntry entry) {
        HealthEntryVersion latest = versionRepo
                .findFirstByHealthEntryIdOrderByVersionNumberDesc(entry.getId())
                .orElse(null);
        return entryMapper.toResponse(entry, latest == null ? null : versionMapper.toResponse(latest));
    }

    private Set<String> allowedCodes(Delegation delegation) {
        return delegation.getCategories().stream().map(HealthCategory::getCode).collect(Collectors.toSet());
    }
}
