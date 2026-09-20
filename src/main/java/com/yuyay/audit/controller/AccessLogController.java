package com.yuyay.audit.controller;

import com.yuyay.audit.dto.AccessLogResponseDTO;
import com.yuyay.audit.service.AccessLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/care-subjects")
@RequiredArgsConstructor
public class AccessLogController {

    private final AccessLogService accessLogService;

    @GetMapping("/{careSubjectId}/access-logs")
    public List<AccessLogResponseDTO> list(
            @PathVariable Long careSubjectId
    ) {
        return accessLogService.list(careSubjectId);
    }
}