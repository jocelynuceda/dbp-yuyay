package com.yuyay.admin.controller;

import com.yuyay.attachment.dto.DocumentScopeStatusResponse;
import com.yuyay.attachment.dto.DocumentScopeSwitchRequest;
import com.yuyay.attachment.scope.DocumentScopeService;
import com.yuyay.security.CurrentUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/document-scope")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDocumentScopeController {

    private final DocumentScopeService scopeService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public DocumentScopeStatusResponse status() {
        return currentStatus();
    }

    @PutMapping
    public DocumentScopeStatusResponse update(@Valid @RequestBody DocumentScopeSwitchRequest request) {
        boolean enabled = scopeService.setEnabled(request.enabled());
        log.info("Administrador {} cambió el clasificador de documentos: activo={}",
                currentUserService.getCurrentUserId(), enabled);
        return currentStatus();
    }

    private DocumentScopeStatusResponse currentStatus() {
        return new DocumentScopeStatusResponse(scopeService.isEnabled(), scopeService.isModelLoaded());
    }
}
