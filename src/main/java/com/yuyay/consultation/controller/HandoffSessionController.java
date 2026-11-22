package com.yuyay.consultation.controller;

import com.yuyay.consultation.dto.CreateHandoffSessionDTO;
import com.yuyay.consultation.dto.HandoffSessionCreatedDTO;
import com.yuyay.consultation.dto.HandoffSessionResponseDTO;
import com.yuyay.consultation.service.HandoffSessionService;
import com.yuyay.security.handoff.HandoffAccessCodeDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HandoffSessionController {

    private final HandoffSessionService handoffSessionService;

    @GetMapping("/handoff-sessions/{sessionId}/access-code")
    public ResponseEntity<HandoffAccessCodeDTO> accessCode(@PathVariable Long sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .body(handoffSessionService.accessCode(sessionId));
    }

    @PostMapping("/handoffs/{handoffId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoffSessionCreatedDTO create(
            @PathVariable Long handoffId,
            @Valid @RequestBody CreateHandoffSessionDTO request
    ) {
        return handoffSessionService.create(handoffId, request);
    }

    @GetMapping("/handoffs/{handoffId}/sessions")
    public List<HandoffSessionResponseDTO> list(
            @PathVariable Long handoffId
    ) {
        return handoffSessionService.list(handoffId);
    }

    @DeleteMapping("/handoff-sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable Long sessionId
    ) {
        handoffSessionService.revoke(sessionId);
    }
}
