package com.yuyay.care.controller;

import com.yuyay.care.dto.DelegationCreateRequest;
import com.yuyay.care.dto.DelegationCreatedResponse;
import com.yuyay.care.dto.DelegationResponse;
import com.yuyay.care.service.DelegationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DelegationController {

    private final DelegationService service;

    @PostMapping("/care-subjects/{subjectId}/delegations")
    public ResponseEntity<DelegationCreatedResponse> create(@PathVariable Long subjectId,
                                                            @Valid @RequestBody DelegationCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(subjectId, req));
    }

    @GetMapping("/care-subjects/{subjectId}/delegations")
    public List<DelegationResponse> listBySubject(@PathVariable Long subjectId) {
        return service.listBySubject(subjectId);
    }

    @DeleteMapping("/delegations/{delegationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long delegationId) {
        service.revoke(delegationId);
    }
}
