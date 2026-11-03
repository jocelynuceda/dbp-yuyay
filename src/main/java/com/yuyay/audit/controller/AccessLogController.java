package com.yuyay.audit.controller;

import com.yuyay.audit.dto.AccessLogResponseDTO;
import com.yuyay.audit.service.AccessLogService;
import lombok.RequiredArgsConstructor;
import com.yuyay.common.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/care-subjects")
@RequiredArgsConstructor
public class AccessLogController {

    private final AccessLogService accessLogService;

    @GetMapping("/{careSubjectId}/access-logs")
    public PageResponse<AccessLogResponseDTO> list(
            @PathVariable Long careSubjectId,
            @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return PageResponse.from(accessLogService.list(careSubjectId, pageable));
    }
}