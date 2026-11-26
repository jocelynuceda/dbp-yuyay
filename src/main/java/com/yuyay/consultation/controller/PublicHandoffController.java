package com.yuyay.consultation.controller;

import com.yuyay.attachment.dto.AttachmentContent;
import com.yuyay.consultation.dto.HandoffPublicViewDTO;
import com.yuyay.consultation.dto.OpenHandoffSessionDTO;
import com.yuyay.consultation.service.HandoffSessionService;
import com.yuyay.security.handoff.HandoffAccessSecurityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/public/handoff-sessions")
@RequiredArgsConstructor
public class PublicHandoffController {

    private final HandoffSessionService handoffSessionService;

    @GetMapping("/{token}/access-requirements")
    public ResponseEntity<AccessRequirements> accessRequirements(@PathVariable String token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(new AccessRequirements(handoffSessionService.accessCodeRequired(token)));
    }

    public record AccessRequirements(boolean accessCodeRequired) {}

    @GetMapping("/{token}/summary.pdf")
    public ResponseEntity<byte[]> summaryPdf(@PathVariable String token,
            @RequestHeader(name = HandoffAccessSecurityService.GRANT_HEADER, required = false) String accessGrant) {
        byte[] pdf = handoffSessionService.summaryPdf(token, accessGrant);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("resumen-clinico-yuyay.pdf").build().toString())
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(CacheControl.noStore())
                .body(pdf);
    }

    /** Acceso por token de handoff vigente, abierto previamente; no requiere una cuenta médica. */
    @GetMapping("/{token}/versions/{versionId}/source/content")
    public ResponseEntity<Resource> sourceContent(@PathVariable String token, @PathVariable Long versionId,
            @RequestHeader(name = HandoffAccessSecurityService.GRANT_HEADER, required = false) String accessGrant) {
        AttachmentContent content = handoffSessionService.sourceContent(token, versionId, accessGrant);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(content.filename(), StandardCharsets.UTF_8).build().toString())
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(CacheControl.noStore())
                .body(new ByteArrayResource(content.bytes()));
    }

    @PostMapping("/{token}/open")
    public ResponseEntity<HandoffPublicViewDTO> open(
            @PathVariable String token,
            @Valid @RequestBody OpenHandoffSessionDTO request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .body(handoffSessionService.open(
                token,
                request.visitorName(),
                request.visitorRole(),
                request.accessCode()
        ));
    }
}
