package com.yuyay.health.controller;

import com.yuyay.health.dto.*;
import com.yuyay.health.service.HealthEntryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.yuyay.common.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/care-subjects/{subjectId}/health-entries")
@RequiredArgsConstructor
public class HealthEntryController {

    private final HealthEntryService service;

    @PostMapping
    public ResponseEntity<HealthEntryResponse> create(
            @PathVariable Long subjectId,
            @Valid @RequestBody HealthEntryCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(subjectId, req));
    }

    @GetMapping
    public PageResponse<HealthEntryResponse> list(
            @PathVariable Long subjectId,
            @RequestParam(required = false) String category,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(service.list(subjectId, category, pageable));
    }

    @GetMapping("/{entryId}")
    public HealthEntryResponse get(
            @PathVariable Long subjectId,
            @PathVariable Long entryId) {
        return service.get(subjectId, entryId);
    }

    @GetMapping("/{entryId}/versions")
    public List<HealthEntryVersionResponse> listVersions(
            @PathVariable Long subjectId,
            @PathVariable Long entryId) {
        return service.listVersions(subjectId, entryId);
    }

    @PatchMapping("/{entryId}")
    public HealthEntryResponse update(
            @PathVariable Long subjectId,
            @PathVariable Long entryId,
            @Valid @RequestBody HealthEntryUpdateRequest req) {
        return service.update(subjectId, entryId, req);
    }

    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long subjectId,
            @PathVariable Long entryId) {
        service.delete(subjectId, entryId);
    }
}