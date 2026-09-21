package com.yuyay.health.service;

import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.health.dto.*;
import com.yuyay.health.entity.*;
import com.yuyay.health.exception.HealthCategoryNotFoundException;
import com.yuyay.health.exception.HealthEntryNotFoundException;
import com.yuyay.health.mapper.HealthEntryMapper;
import com.yuyay.health.mapper.HealthEntryVersionMapper;
import com.yuyay.health.repository.HealthCategoryRepository;
import com.yuyay.health.repository.HealthEntryRepository;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import com.yuyay.event.HealthEntryChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class HealthEntryService {

    private final HealthEntryRepository entryRepo;
    private final HealthEntryVersionRepository versionRepo;
    private final HealthCategoryRepository categoryRepo;
    private final CareSubjectRepository careSubjectRepo;
    private final UserRepository userRepo;
    private final HealthEntryMapper entryMapper;
    private final HealthEntryVersionMapper versionMapper;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;
    private final ApplicationEventPublisher eventPublisher;

    public HealthEntryResponse create(Long subjectId, HealthEntryCreateRequest req) {
        Long meId = currentUserService.getCurrentUserId();
        User me = loadUser(meId);
        CareSubject cs = loadSubject(subjectId);
        authorizationService.requireAccess(meId, subjectId);

        HealthCategory cat = categoryRepo.findByCode(req.categoryCode())
                .orElseThrow(() -> new HealthCategoryNotFoundException(req.categoryCode()));

        HealthEntry entry = HealthEntry.builder()
                .careSubject(cs)
                .category(cat)
                .createdBy(me)
                .build();
        entryRepo.save(entry);

        HealthEntryVersion v1 = HealthEntryVersion.builder()
                .healthEntry(entry)
                .versionNumber(1)
                .changeType(ChangeType.CREATED)
                .title(req.title())
                .details(req.details())
                .dose(req.dose())
                .frequency(req.frequency())
                .occurredOn(req.occurredOn())
                .confidenceLevel(req.confidenceLevel())
                .notes(req.notes())
                .declaredBy(me)
                .build();
        versionRepo.save(v1);

        publishChange(entry, me, ChangeType.CREATED, v1.getTitle());
        return entryMapper.toResponse(entry, versionMapper.toResponse(v1));
    }

    @Transactional(readOnly = true)
    public List<HealthEntryResponse> list(Long subjectId, String categoryCode) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);

        List<HealthEntry> entries = (categoryCode == null || categoryCode.isBlank())
                ? entryRepo.findByCareSubjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(subjectId)
                : entryRepo.findByCareSubjectIdAndCategoryCodeAndDeletedAtIsNullOrderByCreatedAtDesc(subjectId, categoryCode);

        return entries.stream()
                .map(e -> {
                    HealthEntryVersion latest = versionRepo
                            .findFirstByHealthEntryIdOrderByVersionNumberDesc(e.getId())
                            .orElse(null);
                    return entryMapper.toResponse(e,
                            latest == null ? null : versionMapper.toResponse(latest));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public HealthEntryResponse get(Long subjectId, Long entryId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);

        HealthEntry entry = entryRepo.findWithCategoryById(entryId)
                .filter(e -> e.getCareSubject().getId().equals(subjectId))
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new HealthEntryNotFoundException(entryId));

        HealthEntryVersion latest = versionRepo
                .findFirstByHealthEntryIdOrderByVersionNumberDesc(entryId)
                .orElse(null);

        return entryMapper.toResponse(entry,
                latest == null ? null : versionMapper.toResponse(latest));
    }

    @Transactional(readOnly = true)
    public List<HealthEntryVersionResponse> listVersions(Long subjectId, Long entryId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);

        HealthEntry entry = entryRepo.findById(entryId)
                .filter(e -> e.getCareSubject().getId().equals(subjectId))
                .orElseThrow(() -> new HealthEntryNotFoundException(entryId));

        return versionRepo.findByHealthEntryIdOrderByVersionNumberDesc(entry.getId()).stream()
                .map(versionMapper::toResponse)
                .toList();
    }

    public HealthEntryResponse update(Long subjectId, Long entryId, HealthEntryUpdateRequest req) {
        Long meId = currentUserService.getCurrentUserId();
        User me = loadUser(meId);
        authorizationService.requireAccess(meId, subjectId);

        HealthEntry entry = entryRepo.findById(entryId)
                .filter(e -> e.getCareSubject().getId().equals(subjectId))
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new HealthEntryNotFoundException(entryId));

        int next = versionRepo.findFirstByHealthEntryIdOrderByVersionNumberDesc(entryId)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);

        HealthEntryVersion v = HealthEntryVersion.builder()
                .healthEntry(entry)
                .versionNumber(next)
                .changeType(ChangeType.UPDATED)
                .title(req.title())
                .details(req.details())
                .dose(req.dose())
                .frequency(req.frequency())
                .occurredOn(req.occurredOn())
                .confidenceLevel(req.confidenceLevel())
                .notes(req.notes())
                .declaredBy(me)
                .build();
        versionRepo.save(v);

        publishChange(entry, me, ChangeType.UPDATED, v.getTitle());
        return entryMapper.toResponse(entry, versionMapper.toResponse(v));
    }

    public void delete(Long subjectId, Long entryId) {
        Long meId = currentUserService.getCurrentUserId();
        User me = loadUser(meId);
        authorizationService.requireAccess(meId, subjectId);

        HealthEntry entry = entryRepo.findById(entryId)
                .filter(e -> e.getCareSubject().getId().equals(subjectId))
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new HealthEntryNotFoundException(entryId));

        HealthEntryVersion last = versionRepo
                .findFirstByHealthEntryIdOrderByVersionNumberDesc(entryId)
                .orElseThrow(() -> new HealthEntryNotFoundException(entryId));

        int next = last.getVersionNumber() + 1;

        HealthEntryVersion v = HealthEntryVersion.builder()
                .healthEntry(entry)
                .versionNumber(next)
                .changeType(ChangeType.DELETED)
                .title(last.getTitle())
                .details(last.getDetails())
                .dose(last.getDose())
                .frequency(last.getFrequency())
                .occurredOn(last.getOccurredOn())
                .confidenceLevel(last.getConfidenceLevel())
                .notes(last.getNotes())
                .declaredBy(me)
                .build();
        versionRepo.save(v);

        entry.setDeletedAt(Instant.now());
        publishChange(entry, me, ChangeType.DELETED, last.getTitle());
    }

    private void publishChange(HealthEntry entry, User actor, ChangeType type, String title) {
        String description = switch (type) {
            case CREATED -> "%s registró \"%s\" (%s)".formatted(actor.getName(), title, entry.getCategory().getName());
            case UPDATED -> "%s actualizó \"%s\" (%s)".formatted(actor.getName(), title, entry.getCategory().getName());
            case DELETED -> "%s eliminó \"%s\" (%s)".formatted(actor.getName(), title, entry.getCategory().getName());
        };
        eventPublisher.publishEvent(new HealthEntryChangedEvent(
                entry.getId(),
                entry.getCareSubject().getId(),
                actor.getId(),
                type.name(),
                description,
                entry.getCareSubject().getName()));
    }

    private User loadUser(Long id) {
        return userRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private CareSubject loadSubject(Long id) {
        return careSubjectRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CareSubject not found: " + id));
    }
}