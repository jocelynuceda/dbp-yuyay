package com.yuyay.consultation.controller;

import com.yuyay.consultation.dto.CreateHandoffDTO;
import com.yuyay.consultation.dto.HandoffDetailDTO;
import com.yuyay.consultation.dto.HandoffResponseDTO;
import com.yuyay.consultation.service.HandoffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HandoffController {

    private final HandoffService handoffService;

    @PostMapping("/care-subjects/{careSubjectId}/handoffs")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoffDetailDTO create(
            @PathVariable Long careSubjectId,
            @Valid @RequestBody CreateHandoffDTO request
    ) {
        return handoffService.create(careSubjectId, request);
    }

    @GetMapping("/care-subjects/{careSubjectId}/handoffs")
    public List<HandoffResponseDTO> list(
            @PathVariable Long careSubjectId
    ) {
        return handoffService.list(careSubjectId);
    }

    @GetMapping("/handoffs/{handoffId}")
    public HandoffDetailDTO get(
            @PathVariable Long handoffId
    ) {
        return handoffService.get(handoffId);
    }

    @DeleteMapping("/handoffs/{handoffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long handoffId
    ) {
        handoffService.delete(handoffId);
    }
}