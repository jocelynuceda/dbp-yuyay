package com.yuyay.attachment.controller;

import com.yuyay.attachment.dto.AttachmentAccessResponse;
import com.yuyay.attachment.dto.AttachmentContent;
import com.yuyay.attachment.dto.AttachmentResponse;
import com.yuyay.attachment.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/care-subjects/{subjectId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {
    private final AttachmentService attachmentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttachmentResponse> upload(@PathVariable Long subjectId,
                                                     @RequestParam("file") MultipartFile file,
                                                     @RequestParam(required = false) Long consultationId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attachmentService.upload(subjectId, file, consultationId));
    }

    @GetMapping
    public List<AttachmentResponse> list(@PathVariable Long subjectId) {
        return attachmentService.list(subjectId);
    }

    @GetMapping("/{attachmentId}")
    public AttachmentResponse get(@PathVariable Long subjectId, @PathVariable Long attachmentId) {
        return attachmentService.get(subjectId, attachmentId);
    }

    @GetMapping("/{attachmentId}/access")
    public ResponseEntity<AttachmentAccessResponse> access(@PathVariable Long subjectId,
                                                           @PathVariable Long attachmentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(attachmentService.access(subjectId, attachmentId));
    }

    /** Devuelve un Resource para que Spring atienda Range: el reproductor de iOS lo necesita. */
    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<Resource> content(@PathVariable Long subjectId, @PathVariable Long attachmentId) {
        AttachmentContent content = attachmentService.content(subjectId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(content.filename(), StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(new ByteArrayResource(content.bytes()));
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable Long subjectId, @PathVariable Long attachmentId) {
        attachmentService.delete(subjectId, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{attachmentId}/ocr-retry")
    public ResponseEntity<AttachmentResponse> retryOcr(@PathVariable Long subjectId,
                                                       @PathVariable Long attachmentId) {
        return ResponseEntity.accepted().body(attachmentService.retryOcr(subjectId, attachmentId));
    }
}
