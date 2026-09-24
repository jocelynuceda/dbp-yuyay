package com.yuyay.event.listener;

import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.ocr.TextExtractor;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.event.AttachmentUploadedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class OcrListener {
    private static final int MAX_ERROR_LENGTH = 500;

    private final AttachmentRepository attachmentRepository;
    private final TextExtractor textExtractor;

    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAttachmentUploaded(AttachmentUploadedEvent event) {
        Attachment attachment = attachmentRepository.findById(event.attachmentId()).orElse(null);
        if (attachment == null) {
            log.warn("Adjunto {} no encontrado para OCR", event.attachmentId());
            return;
        }
        try {
            attachment.setOcrText(textExtractor.extractText(attachment.getStorageKey()));
            attachment.setOcrStatus(OcrStatus.DONE);
            attachment.setOcrError(null);
        } catch (Exception e) {
            log.error("Fallo el OCR del adjunto {}", attachment.getId(), e);
            attachment.setOcrStatus(OcrStatus.FAILED);
            attachment.setOcrError(truncate(e.getMessage()));
        }
        attachment.setOcrCompletedAt(Instant.now());
    }

    private String truncate(String message) {
        if (message == null) return "Error desconocido en el OCR";
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
