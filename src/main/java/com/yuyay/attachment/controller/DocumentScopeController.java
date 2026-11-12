package com.yuyay.attachment.controller;

import com.yuyay.attachment.dto.DocumentScopeSummaryResponse;
import com.yuyay.attachment.scope.DocumentScopeSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/document-scope")
@RequiredArgsConstructor
public class DocumentScopeController {
    private final DocumentScopeSummaryService summaryService;

    @GetMapping
    public DocumentScopeSummaryResponse summary() {
        return summaryService.summary();
    }
}
