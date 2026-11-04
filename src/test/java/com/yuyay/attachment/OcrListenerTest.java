package com.yuyay.attachment;

import com.yuyay.attachment.ocr.TextExtractor;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.event.AttachmentUploadedEvent;
import com.yuyay.event.listener.OcrListener;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OcrListenerTest {
    @Test
    void eventDeliveredAfterDeletionDoesNotReadStorageOrRecreateMetadata() {
        var repository = mock(AttachmentRepository.class);
        var extractor = mock(TextExtractor.class);
        when(repository.findForUpdateById(7L)).thenReturn(Optional.empty());

        new OcrListener(repository, extractor).onAttachmentUploaded(new AttachmentUploadedEvent(7L));

        verify(repository).findForUpdateById(7L);
        verifyNoInteractions(extractor);
    }
}
