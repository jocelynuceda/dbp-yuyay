package com.yuyay.care.controller;

import com.yuyay.care.dto.CareRelationshipInviteRequest;
import com.yuyay.care.dto.CareRelationshipResponse;
import com.yuyay.care.service.CareRelationshipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CareRelationshipController {

    private final CareRelationshipService service;

    @PostMapping("/care-subjects/{subjectId}/relationships")
    public ResponseEntity<CareRelationshipResponse> invite(
            @PathVariable Long subjectId,
            @Valid @RequestBody CareRelationshipInviteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.invite(subjectId, req));
    }

    @GetMapping("/care-subjects/{subjectId}/relationships")
    public List<CareRelationshipResponse> listBySubject(@PathVariable Long subjectId) {
        return service.listBySubject(subjectId);
    }

    @GetMapping("/me/relationships/pending")
    public List<CareRelationshipResponse> listMyPending() {
        return service.listMyPending();
    }

    @PostMapping("/me/relationships/{relationshipId}/accept")
    public CareRelationshipResponse accept(@PathVariable Long relationshipId) {
        return service.accept(relationshipId);
    }

    @DeleteMapping("/care-subjects/{subjectId}/relationships/{relationshipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long subjectId, @PathVariable Long relationshipId) {
        service.revoke(subjectId, relationshipId);
    }
}