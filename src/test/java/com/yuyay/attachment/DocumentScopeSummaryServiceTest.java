package com.yuyay.attachment;

import com.yuyay.attachment.dto.DocumentScopeSummaryResponse;
import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.attachment.scope.DocumentScopeService;
import com.yuyay.attachment.scope.DocumentScopeSummaryService;
import com.yuyay.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentScopeSummaryServiceTest {
    private static final String PRESCRIPTION =
            "Rp. Amoxicilina 500 mg cápsulas. Tomar 1 cada 8 horas por 7 días. Dr. Mario Demo CMP 00001";
    private static final String MENU = "Menú del día: sopa de pollo, arroz con pollo, refresco de maracuyá. S/ 12.00";
    private static final String GIBBERISH = "zzzzqqqq wwwwkkkk xxxxjjjj vvvvyyyy 1234567890";

    private final AttachmentRepository repository = mock(AttachmentRepository.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);

    @Test
    void countsTheCurrentUsersDocumentsByLabelAndSkipsAudio() {
        when(currentUser.getCurrentUserId()).thenReturn(5L);
        when(repository.findByUploadedByIdAndOcrStatus(5L, OcrStatus.DONE)).thenReturn(List.of(
                attachment(1L, "image/png", PRESCRIPTION),
                attachment(2L, "application/pdf", PRESCRIPTION),
                attachment(3L, "image/jpeg", MENU),
                attachment(4L, "image/png", GIBBERISH),
                attachment(5L, "image/png", null),
                attachment(6L, "audio/mp4", null)));
        var service = new DocumentScopeSummaryService(scopeService(true), repository, currentUser);

        assertEquals(new DocumentScopeSummaryResponse(true, 2, 2, 1), service.summary());
    }

    @Test
    void switchedOffItReturnsZerosWithoutReadingDocuments() {
        var service = new DocumentScopeSummaryService(scopeService(false), repository, currentUser);

        assertEquals(new DocumentScopeSummaryResponse(false, 0, 0, 0), service.summary());
        verifyNoInteractions(repository, currentUser);
    }

    private static DocumentScopeService scopeService(boolean enabled) {
        return new DocumentScopeService(JsonMapper.builder().build(), enabled, 0.50, 0.65);
    }

    private static Attachment attachment(Long id, String contentType, String ocrText) {
        return Attachment.builder().id(id).contentType(contentType).ocrStatus(OcrStatus.DONE).ocrText(ocrText).build();
    }
}
