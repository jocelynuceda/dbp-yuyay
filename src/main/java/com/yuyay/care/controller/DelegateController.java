package com.yuyay.care.controller;

import com.yuyay.care.dto.DelegateMeResponse;
import com.yuyay.care.service.DelegationService;
import com.yuyay.health.dto.HealthEntryResponse;
import com.yuyay.health.service.DelegateHealthEntryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/delegate")
@RequiredArgsConstructor
public class DelegateController {

    private final DelegationService delegationService;
    private final DelegateHealthEntryService healthEntryService;

    @GetMapping("/me")
    public DelegateMeResponse me() {
        return delegationService.me();
    }

    @GetMapping("/health-entries")
    public List<HealthEntryResponse> listHealthEntries(@RequestParam(required = false) String category) {
        return healthEntryService.list(category);
    }

    @GetMapping("/health-entries/{entryId}")
    public HealthEntryResponse getHealthEntry(@PathVariable Long entryId) {
        return healthEntryService.get(entryId);
    }
}
