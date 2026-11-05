package com.yuyay.attachment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.mapper.AttachmentMapper;
import com.yuyay.attachment.ocr.TextExtractor;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.event.AttachmentUploadedEvent;
import com.yuyay.event.listener.OcrListener;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.textract.model.TextractException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OcrListenerTest {
    private static final String PRIVATE_DETAILS = "https://private.invalid/file?X-Amz-Signature=secret-token "
            + "Authorization=secret-token OCR=private-clinical-text\nforged-log-line";
    private static final String SAFE_ERROR = "No se pudo extraer el texto. Puedes reintentar el OCR.";

    @Test
    void eventDeliveredAfterDeletionDoesNotReadStorageOrRecreateMetadata() {
        var repository = mock(AttachmentRepository.class);
        var extractor = mock(TextExtractor.class);
        when(repository.findForUpdateById(7L)).thenReturn(Optional.empty());

        new OcrListener(repository, extractor).onAttachmentUploaded(new AttachmentUploadedEvent(7L));

        verify(repository).findForUpdateById(7L);
        verifyNoInteractions(extractor);
    }

    @Test
    void awsFailureIncludesAttachmentAndSafeDiagnosticMetadataWithoutRawDetails() {
        var error = TextractException.builder().message(PRIVATE_DETAILS)
                .requestId("request-1234").statusCode(429)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ThrottlingException")
                        .errorMessage(PRIVATE_DETAILS).serviceName("Textract").build()).build();

        var event = captureFailure(new IllegalStateException(PRIVATE_DETAILS, error));

        assertEquals("Fallo OCR adjunto=7 exceptionType=IllegalStateException causeType=TextractException awsExceptionType=TextractException "
                + "awsErrorCode=ThrottlingException awsStatus=429 awsRequestId=request-1234", event.getFormattedMessage());
        assertSafeLog(event);
    }

    @Test
    void nonAwsFailureStillIdentifiesAttachmentAndExceptionType() {
        var event = captureFailure(new IllegalArgumentException(PRIVATE_DETAILS));

        assertEquals("Fallo OCR adjunto=7 exceptionType=IllegalArgumentException causeType=IllegalArgumentException", event.getFormattedMessage());
        assertSafeLog(event);
    }

    @Test
    void clientFailureIdentifiesTimeoutBehindWrapperWithoutExposingMessages() {
        var timeout = new java.net.SocketTimeoutException(PRIVATE_DETAILS);
        var clientError = SdkClientException.builder().message(PRIVATE_DETAILS).cause(timeout).build();

        var event = captureFailure(new IllegalStateException(PRIVATE_DETAILS, clientError));

        assertEquals("Fallo OCR adjunto=7 exceptionType=IllegalStateException causeType=SocketTimeoutException", event.getFormattedMessage());
        assertSafeLog(event);
    }

    @Test
    void untrustedOrOversizedAwsMetadataIsRejectedWithoutPartialLogging() {
        var error = TextractException.builder().message(PRIVATE_DETAILS).requestId(PRIVATE_DETAILS)
                .statusCode(500).awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("A".repeat(1000)).errorMessage(PRIVATE_DETAILS).build()).build();

        var event = captureFailure(error);

        assertTrue(event.getFormattedMessage().contains("awsErrorCode=unavailable awsStatus=500 awsRequestId=unavailable"));
        assertFalse(event.getFormattedMessage().contains("AAAA"));
        assertSafeLog(event);
    }

    @Test
    void sdkServiceFailureWithMissingAwsDetailsStillIncludesStatusAndRequestId() {
        var error = SdkServiceException.builder().message(PRIVATE_DETAILS)
                .requestId("request-5678").statusCode(503).build();

        var event = captureFailure(error);

        assertTrue(event.getFormattedMessage().contains("awsErrorCode=unavailable awsStatus=503 awsRequestId=request-5678"));
        assertSafeLog(event);
    }

    @Test
    void cyclicCauseChainDoesNotHangOrExposeItsMessages() {
        var first = new RuntimeException(PRIVATE_DETAILS);
        var second = new IllegalStateException(PRIVATE_DETAILS, first);
        first.initCause(second);

        var event = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> captureFailure(first));

        assertEquals("Fallo OCR adjunto=7 exceptionType=RuntimeException causeType=IllegalStateException", event.getFormattedMessage());
        assertSafeLog(event);
    }

    private ILoggingEvent captureFailure(Exception error) {
        var repository = mock(AttachmentRepository.class);
        var extractor = mock(TextExtractor.class);
        var attachment = Attachment.builder().id(7L).storageKey("care-subjects/7/private.jpg").build();
        when(repository.findForUpdateById(7L)).thenReturn(Optional.of(attachment));
        when(extractor.extractText(attachment.getStorageKey())).thenThrow(error);
        var logger = (Logger) LoggerFactory.getLogger(OcrListener.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new OcrListener(repository, extractor).onAttachmentUploaded(new AttachmentUploadedEvent(7L));
            assertEquals(OcrStatus.FAILED, attachment.getOcrStatus());
            assertEquals(SAFE_ERROR, attachment.getOcrError());
            assertEquals(SAFE_ERROR, Mappers.getMapper(AttachmentMapper.class).toResponse(attachment).ocrError());
            assertEquals(1, appender.list.size());
            return appender.list.getFirst();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void assertSafeLog(ILoggingEvent event) {
        assertEquals(Level.WARN, event.getLevel());
        assertNull(event.getThrowableProxy());
        for (String privateValue : new String[]{"https://", "secret-token", "private-clinical-text", "forged-log-line", "care-subjects/"}) {
            assertFalse(event.getFormattedMessage().contains(privateValue));
        }
    }
}
