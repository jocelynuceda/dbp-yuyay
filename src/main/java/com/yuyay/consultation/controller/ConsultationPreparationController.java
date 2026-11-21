package com.yuyay.consultation.controller;

import com.yuyay.consultation.dto.ConsultationPreparationDTO;
import com.yuyay.consultation.service.ConsultationPreparationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/consultations")
@RequiredArgsConstructor
public class ConsultationPreparationController {
    private final ConsultationPreparationService service;

    @GetMapping("/{id}/preparation")
    public ConsultationPreparationDTO prepare(@PathVariable Long id) {
        return service.prepare(id);
    }
}
