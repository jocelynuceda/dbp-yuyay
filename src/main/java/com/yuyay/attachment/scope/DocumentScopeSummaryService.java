package com.yuyay.attachment.scope;

import com.yuyay.attachment.dto.DocumentScopeSummaryResponse;
import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentScopeSummaryService {
    private final DocumentScopeService scopeService;
    private final AttachmentRepository attachmentRepo;
    private final CurrentUserService currentUserService;

    /** Cuenta los adjuntos con OCR terminado que subió el usuario actual: MEDICAL, REVIEW y NONMEDICAL. */
    @Transactional(readOnly = true)
    public DocumentScopeSummaryResponse summary() {
        if (!scopeService.isEnabled()) {
            return new DocumentScopeSummaryResponse(false, 0, 0, 0);
        }
        Long meId = currentUserService.getCurrentUserId();
        List<Attachment> attachments = attachmentRepo.findByUploadedByIdAndOcrStatus(meId, OcrStatus.DONE);
        long appropriate = 0;
        long unsure = 0;
        long strikes = 0;
        for (Attachment attachment : attachments) {
            if (attachment.getContentType() != null && attachment.getContentType().startsWith("audio/")) continue;
            try {
                DocumentScopeLabel label = scopeService.classify(attachment.getOcrText())
                        .map(DocumentScopeResult::label).orElse(null);
                if (label == DocumentScopeLabel.MEDICAL) appropriate++;
                else if (label == DocumentScopeLabel.REVIEW) unsure++;
                else if (label == DocumentScopeLabel.NONMEDICAL) strikes++;
            } catch (RuntimeException e) {
                log.warn("No se pudo clasificar el adjunto {}: {}", attachment.getId(), e.getClass().getSimpleName());
            }
        }
        return new DocumentScopeSummaryResponse(true, appropriate, unsure, strikes);
    }
}
