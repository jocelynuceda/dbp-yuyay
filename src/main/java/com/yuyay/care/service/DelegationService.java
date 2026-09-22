package com.yuyay.care.service;

import com.yuyay.audit.entity.AccessAction;
import com.yuyay.care.dto.DelegateMeResponse;
import com.yuyay.care.dto.DelegateSessionResponse;
import com.yuyay.care.dto.DelegationCreateRequest;
import com.yuyay.care.dto.DelegationCreatedResponse;
import com.yuyay.care.dto.DelegationResponse;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.entity.Delegation;
import com.yuyay.care.exception.CareSubjectNotFoundException;
import com.yuyay.care.mapper.DelegationMapper;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.care.repository.DelegationRepository;
import com.yuyay.config.AppProperties;
import com.yuyay.event.AccessRecordedEvent;
import com.yuyay.event.DelegationCreatedEvent;
import com.yuyay.exception.DelegationExpiredException;
import com.yuyay.exception.InvalidDelegationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.health.entity.HealthCategory;
import com.yuyay.health.repository.HealthCategoryRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.security.DelegatePrincipal;
import com.yuyay.security.JwtService;
import com.yuyay.security.TokenHasher;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class DelegationService {

    private final DelegationRepository delegationRepo;
    private final CareSubjectRepository subjectRepo;
    private final HealthCategoryRepository categoryRepo;
    private final UserRepository userRepo;
    private final DelegationMapper mapper;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;
    private final JwtService jwtService;
    private final AppProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public DelegationCreatedResponse create(Long subjectId, DelegationCreateRequest req) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireRole(meId, subjectId, CareRole.PRINCIPAL);

        CareSubject subject = subjectRepo.findById(subjectId)
                .orElseThrow(() -> new CareSubjectNotFoundException(subjectId));
        User me = userRepo.findById(meId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        Set<HealthCategory> categories = resolveCategories(req.categoryCodes());

        String rawToken = TokenHasher.randomToken();
        Delegation delegation = delegationRepo.save(Delegation.builder()
                .careSubject(subject)
                .grantedBy(me)
                .granteeName(req.granteeName())
                .granteeEmail(req.granteeEmail().toLowerCase())
                .tokenHash(TokenHasher.sha256(rawToken))
                .validUntil(req.validUntil())
                .categories(categories)
                .build());

        String shareLink = properties.baseUrl() + "/public/delegations/" + rawToken;
        eventPublisher.publishEvent(new DelegationCreatedEvent(delegation.getId(), shareLink));

        return new DelegationCreatedResponse(mapper.toResponse(delegation), rawToken, shareLink);
    }

    @Transactional(readOnly = true)
    public List<DelegationResponse> listBySubject(Long subjectId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);

        return delegationRepo.findByCareSubjectIdOrderByCreatedAtDesc(subjectId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    public void revoke(Long delegationId) {
        Long meId = currentUserService.getCurrentUserId();
        Delegation delegation = delegationRepo.findWithCategoriesById(delegationId)
                .orElseThrow(() -> new ResourceNotFoundException("Delegación no encontrada"));
        authorizationService.requireRole(meId, delegation.getCareSubject().getId(), CareRole.PRINCIPAL);

        if (delegation.getRevokedAt() != null) {
            throw new InvalidDelegationException("La delegación ya fue revocada");
        }
        delegation.setRevokedAt(Instant.now());
    }

    public DelegateSessionResponse exchange(String rawToken) {
        Delegation delegation = delegationRepo.findByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(() -> new ResourceNotFoundException("Enlace de delegación no encontrado"));

        if (!delegation.isValidNow()) {
            throw new DelegationExpiredException("La delegación fue revocada o ya no está vigente");
        }
        if (delegation.getExchangedAt() != null) {
            throw new InvalidDelegationException("Este enlace ya fue utilizado");
        }
        delegation.setExchangedAt(Instant.now());

        CareSubject subject = delegation.getCareSubject();
        String jwt = jwtService.generateDelegateToken(
                delegation.getId(), subject.getId(), delegation.getGranteeName(), delegation.getValidUntil());

        eventPublisher.publishEvent(AccessRecordedEvent.byDelegation(
                subject.getId(), delegation.getId(), AccessAction.EXCHANGE_DELEGATION, "DELEGATION", delegation.getId()));

        return new DelegateSessionResponse(jwt, "Bearer", delegation.getValidUntil(),
                subject.getId(), subject.getName(), delegation.getGranteeName(), codesOf(delegation));
    }

    @Transactional(readOnly = true)
    public DelegateMeResponse me() {
        DelegatePrincipal principal = currentUserService.getDelegatePrincipal();
        Delegation delegation = authorizationService.requireDelegateAccess(
                principal.delegationId(), principal.careSubjectId(), null);
        CareSubject subject = delegation.getCareSubject();

        return new DelegateMeResponse(delegation.getId(), subject.getId(), subject.getName(),
                delegation.getGranteeName(), codesOf(delegation), delegation.getValidUntil());
    }

    private Set<HealthCategory> resolveCategories(Set<String> codes) {
        List<HealthCategory> found = categoryRepo.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            Set<String> known = found.stream().map(HealthCategory::getCode).collect(Collectors.toSet());
            Set<String> unknown = new HashSet<>(codes);
            unknown.removeAll(known);
            throw new InvalidDelegationException("Categorías desconocidas: " + unknown);
        }
        return new HashSet<>(found);
    }

    private Set<String> codesOf(Delegation delegation) {
        return delegation.getCategories().stream().map(HealthCategory::getCode).collect(Collectors.toSet());
    }
}
