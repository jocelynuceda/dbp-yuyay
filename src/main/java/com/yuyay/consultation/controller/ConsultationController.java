package com.yuyay.consultation.controller;

import com.yuyay.consultation.dto.ConsultationDetailDTO;
import com.yuyay.consultation.dto.ConsultationResponseDTO;
import com.yuyay.consultation.dto.CreateConsultationDTO;
import com.yuyay.consultation.dto.UpdateConsultationDTO;
import com.yuyay.consultation.service.ConsultationService;
import com.yuyay.health.dto.HealthEntryVersionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConsultationController {

    private final ConsultationService consultationService;

    @PostMapping("/care-subjects/{careSubjectId}/consultations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultationDetailDTO create(
            @PathVariable Long careSubjectId,
            @Valid @RequestBody CreateConsultationDTO request
    ) {
        return consultationService.create(careSubjectId, request);
    }

    @GetMapping("/care-subjects/{careSubjectId}/consultations")
    public List<ConsultationResponseDTO> list(
            @PathVariable Long careSubjectId
    ) {
        return consultationService.list(careSubjectId);
    }

    @GetMapping("/consultations/{consultationId}")
    public ConsultationDetailDTO get(
            @PathVariable Long consultationId
    ) {
        return consultationService.get(consultationId);
    }

    @PatchMapping("/consultations/{consultationId}")
    public ConsultationDetailDTO update(
            @PathVariable Long consultationId,
            @Valid @RequestBody UpdateConsultationDTO request
    ) {
        return consultationService.update(consultationId, request);
    }

    @DeleteMapping("/consultations/{consultationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long consultationId
    ) {
        consultationService.delete(consultationId);
    }

    @GetMapping("/care-subjects/{careSubjectId}/consultations/changes")
    public List<HealthEntryVersionResponse> changes(
            @PathVariable Long careSubjectId,
            @RequestParam(defaultValue = "0") Long sinceConsultationId
    ) {
        return consultationService.changes(careSubjectId, sinceConsultationId);
    }
}