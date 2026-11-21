package com.yuyay.consultation.controller;

import com.yuyay.consultation.dto.ConfirmPreparationDTO;
import com.yuyay.consultation.dto.ConsultationPreparationDTO;
import com.yuyay.consultation.dto.HandoffDetailDTO;
import com.yuyay.consultation.service.ConsultationConfirmationService;
import com.yuyay.consultation.service.ConsultationPreparationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/consultations")
@RequiredArgsConstructor
public class ConsultationPreparationController {
    private final ConsultationPreparationService service;
    private final ConsultationConfirmationService confirmation;

    @GetMapping("/{id}/preparation")
    public ConsultationPreparationDTO prepare(@PathVariable Long id) {
        return service.prepare(id);
    }

    @PostMapping("/{id}/preparation/confirm")
    public HandoffDetailDTO confirm(@PathVariable Long id, @Valid @RequestBody ConfirmPreparationDTO request) {
        return confirmation.confirm(id, request);
    }
}
