package com.yuyay.care.controller;

import com.yuyay.care.dto.CareAuthorizationDeclarationResponse;
import com.yuyay.care.dto.CareAuthorizationPolicyResponse;
import com.yuyay.care.dto.CareAuthorizationUpdateRequest;
import com.yuyay.care.dto.CareSubjectCreateRequest;
import com.yuyay.care.dto.CareSubjectResponse;
import com.yuyay.care.dto.CareSubjectUpdateRequest;
import com.yuyay.care.dto.ClaimInvitationCreateRequest;
import com.yuyay.care.dto.ClaimInvitationCreatedResponse;
import com.yuyay.care.dto.ClaimInvitationResponse;
import com.yuyay.care.dto.IdentityCheckRequest;
import com.yuyay.care.dto.IdentityCheckResponse;
import com.yuyay.care.service.CareAuthorizationDeclarationService;
import com.yuyay.care.service.CareSubjectIdentityCheckService;
import com.yuyay.care.service.ClaimInvitationService;
import com.yuyay.care.service.CareSubjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/care-subjects")
@RequiredArgsConstructor
public class CareSubjectController {

    private final CareSubjectService service;
    private final CareAuthorizationDeclarationService declarations;
    private final CareSubjectIdentityCheckService identityChecks;
    private final ClaimInvitationService claimInvitations;

    @PostMapping
    public ResponseEntity<CareSubjectResponse> create(@Valid @RequestBody CareSubjectCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping
    public List<CareSubjectResponse> listMine() {
        return service.listMine();
    }

    @GetMapping("/authorization-policy")
    public CareAuthorizationPolicyResponse getAuthorizationPolicy() {
        return declarations.getPolicy();
    }

    @GetMapping("/{id}/authorization-declarations")
    public List<CareAuthorizationDeclarationResponse> getAuthorizationDeclarations(@PathVariable Long id) {
        return declarations.history(id);
    }

    @PostMapping("/{id}/authorization-declarations")
    public CareAuthorizationDeclarationResponse declareAuthorization(
            @PathVariable Long id, @Valid @RequestBody CareAuthorizationUpdateRequest req) {
        return declarations.declare(id, req);
    }

    @PostMapping("/{id}/identity-check")
    public IdentityCheckResponse checkIdentity(
            @PathVariable Long id, @Valid @RequestBody IdentityCheckRequest req) {
        return identityChecks.check(id, req);
    }

    @PostMapping("/{id}/claim-invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimInvitationCreatedResponse emitClaimInvitation(
            @PathVariable Long id, @Valid @RequestBody ClaimInvitationCreateRequest req) {
        return claimInvitations.emit(id, req);
    }

    @GetMapping("/{id}/claim-invitations")
    public List<ClaimInvitationResponse> listClaimInvitations(@PathVariable Long id) {
        return claimInvitations.listBySubject(id);
    }

    @DeleteMapping("/{id}/claim-invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeClaimInvitation(@PathVariable Long id, @PathVariable Long invitationId) {
        claimInvitations.revoke(id, invitationId);
    }

    @GetMapping("/{id}")
    public CareSubjectResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public CareSubjectResponse update(@PathVariable Long id,
                                      @Valid @RequestBody CareSubjectUpdateRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
