package com.yuyay.consultation.controller;

import com.yuyay.consultation.dto.HandoffPublicViewDTO;
import com.yuyay.consultation.dto.OpenHandoffSessionDTO;
import com.yuyay.consultation.service.HandoffSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/handoff-sessions")
@RequiredArgsConstructor
public class PublicHandoffController {

    private final HandoffSessionService handoffSessionService;

    @PostMapping("/{token}/open")
    public HandoffPublicViewDTO open(
            @PathVariable String token,
            @Valid @RequestBody OpenHandoffSessionDTO request
    ) {
        return handoffSessionService.open(
                token,
                request.visitorName(),
                request.visitorRole()
        );
    }
}