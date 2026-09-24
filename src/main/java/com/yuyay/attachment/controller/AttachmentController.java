package com.yuyay.attachment.controller;

import com.yuyay.attachment.dto.AttachmentResponse;
import com.yuyay.attachment.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    @PostMapping("/{attachmentId}/ocr-retry")
    public ResponseEntity<AttachmentResponse> retryOcr(@PathVariable Long subjectId,
                                                       @PathVariable Long attachmentId) {
        return ResponseEntity.accepted().body(attachmentService.retryOcr(subjectId, attachmentId));
    }
}
