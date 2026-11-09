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
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkServiceException;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class OcrListener {
    private final AttachmentRepository attachmentRepository;
    private final TextExtractor textExtractor;

    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAttachmentUploaded(AttachmentUploadedEvent event) {
        Attachment attachment = attachmentRepository.findForUpdateById(event.attachmentId()).orElse(null);
        if (attachment == null) {
            log.warn("Adjunto {} no encontrado para OCR", event.attachmentId());
            return;
        }
        try {
            attachment.setOcrText(textExtractor.extractText(attachment.getStorageKey()));
            attachment.setOcrStatus(OcrStatus.DONE);
            attachment.setOcrError(null);
        } catch (Exception e) {
            logFailure(attachment.getId(), e);
            attachment.setOcrStatus(OcrStatus.FAILED);
            attachment.setOcrError("No se pudo extraer el texto. Puedes reintentar el OCR.");
        }
        attachment.setOcrCompletedAt(Instant.now());
    }

    private void logFailure(Long attachmentId, Exception error) {
        String exceptionType = safeLogToken(error.getClass().getSimpleName(), 128);
        String causeType = lastCauseType(error);
        SdkServiceException serviceError = findServiceError(error);
        if (serviceError == null) {
            log.warn("Fallo OCR adjunto={} exceptionType={} causeType={}", attachmentId, exceptionType, causeType);
            return;
        }
        String errorCode = serviceError instanceof AwsServiceException awsError && awsError.awsErrorDetails() != null
                ? awsError.awsErrorDetails().errorCode() : null;
        // No pasar el Throwable: sus mensajes, causas o respuesta HTTP pueden contener
        // URLs firmadas, credenciales o contenido clínico. El requestId permite correlación en AWS.
        log.warn("Fallo OCR adjunto={} exceptionType={} causeType={} awsExceptionType={} awsErrorCode={} awsStatus={} awsRequestId={}",
                attachmentId, exceptionType, causeType, safeLogToken(serviceError.getClass().getSimpleName(), 128),
                safeLogToken(errorCode, 64), serviceError.statusCode(), safeLogToken(serviceError.requestId(), 128));
    }

    private SdkServiceException findServiceError(Throwable error) {
        // Límite también para cadenas de causas cíclicas o patológicamente profundas.
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof SdkServiceException serviceError) return serviceError;
        }
        return null;
    }

    private String lastCauseType(Throwable error) {
        String type = "unavailable";
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            type = safeLogToken(error.getClass().getSimpleName(), 128);
        }
        return type;
    }

    private String safeLogToken(String value, int maxLength) {
        // Rechazar el campo entero impide log injection y evita copiar fragmentos de datos sensibles.
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.matches("[A-Za-z0-9_.-]+")) return "unavailable";
        return value;
    }
}
