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
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class OcrListener {
    // Solo tipos MIME simples en minúscula, p. ej. application/pdf o image/svg+xml.
    private static final Pattern SAFE_CONTENT_TYPE = Pattern.compile("[a-z]+/[a-z0-9.+-]+");

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
        long started = System.nanoTime();
        try {
            String text = textExtractor.extractText(attachment.getStorageKey());
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            attachment.setOcrText(text);
            attachment.setOcrStatus(OcrStatus.DONE);
            attachment.setOcrError(null);
            // Solo la longitud: nunca se registra el texto extraído (puede ser clínico).
            log.info("OCR listo adjunto={} contentType={} sizeBytes={} width={} height={} source={} client={} durationMs={} characters={}",
                    attachment.getId(), safeContentType(attachment.getContentType()),
                    sizeOrUnavailable(attachment.getSizeBytes()), dimensionOrUnavailable(attachment.getImageWidth()),
                    dimensionOrUnavailable(attachment.getImageHeight()), sourceOrUnavailable(attachment),
                    clientOrUnavailable(attachment), durationMs,
                    text == null ? 0 : text.length());
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            logFailure(attachment, durationMs, e);
            attachment.setOcrStatus(OcrStatus.FAILED);
            attachment.setOcrError("No se pudo extraer el texto. Puedes reintentar el OCR.");
        }
        attachment.setOcrCompletedAt(Instant.now());
    }

    private void logFailure(Attachment attachment, long durationMs, Exception error) {
        Long attachmentId = attachment.getId();
        String contentType = safeContentType(attachment.getContentType());
        String sizeBytes = sizeOrUnavailable(attachment.getSizeBytes());
        String width = dimensionOrUnavailable(attachment.getImageWidth());
        String height = dimensionOrUnavailable(attachment.getImageHeight());
        String source = sourceOrUnavailable(attachment);
        String client = clientOrUnavailable(attachment);
        String exceptionType = safeLogToken(error.getClass().getSimpleName(), 128);
        String causeType = lastCauseType(error);
        SdkServiceException serviceError = findServiceError(error);
        if (serviceError == null) {
            log.warn("Fallo OCR adjunto={} contentType={} sizeBytes={} width={} height={} source={} client={} durationMs={} exceptionType={} causeType={}",
                    attachmentId, contentType, sizeBytes, width, height, source, client, durationMs, exceptionType, causeType);
            return;
        }
        String errorCode = serviceError instanceof AwsServiceException awsError && awsError.awsErrorDetails() != null
                ? awsError.awsErrorDetails().errorCode() : null;
        // No pasar el Throwable: sus mensajes, causas o respuesta HTTP pueden contener
        // URLs firmadas, credenciales o contenido clínico. El requestId permite correlación en AWS.
        log.warn("Fallo OCR adjunto={} contentType={} sizeBytes={} width={} height={} source={} client={} durationMs={} exceptionType={} causeType={} awsExceptionType={} awsErrorCode={} awsStatus={} awsRequestId={}",
                attachmentId, contentType, sizeBytes, width, height, source, client, durationMs, exceptionType, causeType, safeLogToken(serviceError.getClass().getSimpleName(), 128),
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

    private String safeContentType(String value) {
        // El tipo MIME lo informa el cliente: solo se registra si tiene forma de tipo/subtipo válida.
        if (value == null || value.length() > 64 || !SAFE_CONTENT_TYPE.matcher(value).matches()) {
            return "unavailable";
        }
        return value;
    }

    private String sizeOrUnavailable(Long size) {
        return size == null ? "unavailable" : String.valueOf(size);
    }

    private String dimensionOrUnavailable(Integer value) {
        return value == null ? "unavailable" : String.valueOf(value);
    }

    private String sourceOrUnavailable(Attachment attachment) {
        String source = attachment.getUploadSource();
        return source == null ? "unavailable" : source;
    }

    // Es el cliente que subió el documento; un reintento de OCR se atribuye en la línea del log de la solicitud.
    private String clientOrUnavailable(Attachment attachment) {
        // El valor guardado ya viene saneado al subir.
        String client = attachment.getUploadClient();
        return client == null ? "unavailable" : client;
    }

    private String safeLogToken(String value, int maxLength) {
        // Rechazar el campo entero impide log injection y evita copiar fragmentos de datos sensibles.
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.matches("[A-Za-z0-9_.-]+")) return "unavailable";
        return value;
    }
}
