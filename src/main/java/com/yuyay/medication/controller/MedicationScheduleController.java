package com.yuyay.medication.controller;

import com.yuyay.medication.dto.AgendaDoseResponse;
import com.yuyay.medication.dto.DoseMarkRequest;
import com.yuyay.medication.dto.MedicationScheduleRequest;
import com.yuyay.medication.dto.MedicationScheduleResponse;
import com.yuyay.medication.service.MedicationScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Horarios de medicamentos y tomas. Ver docs/medication-schedule.md. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MedicationScheduleController {
    private final MedicationScheduleService service;

    @GetMapping("/care-subjects/{subjectId}/medication-schedules")
    public List<MedicationScheduleResponse> list(@PathVariable Long subjectId) {
        return service.listForSubject(subjectId);
    }

    @GetMapping("/care-subjects/{subjectId}/health-entries/{entryId}/schedule")
    public MedicationScheduleResponse get(@PathVariable Long subjectId, @PathVariable Long entryId) {
        return service.get(subjectId, entryId);
    }

    @PutMapping("/care-subjects/{subjectId}/health-entries/{entryId}/schedule")
    public MedicationScheduleResponse put(@PathVariable Long subjectId, @PathVariable Long entryId,
                                          @Valid @RequestBody MedicationScheduleRequest request) {
        return service.put(subjectId, entryId, request);
    }

    @DeleteMapping("/care-subjects/{subjectId}/health-entries/{entryId}/schedule")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long subjectId, @PathVariable Long entryId) {
        service.delete(subjectId, entryId);
    }

    /** Tomas de todas las personas que cuido. {@code to} es opcional: por defecto, el mismo día. */
    @GetMapping("/me/medication-agenda")
    public List<AgendaDoseResponse> agenda(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.agenda(from, to == null ? from : to);
    }

    @PutMapping("/medication-schedules/{scheduleId}/doses")
    public AgendaDoseResponse mark(@PathVariable Long scheduleId, @Valid @RequestBody DoseMarkRequest request) {
        return service.mark(scheduleId, request);
    }

    @DeleteMapping("/medication-schedules/{scheduleId}/doses")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmark(@PathVariable Long scheduleId,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time) {
        service.unmark(scheduleId, date, time);
    }
}
