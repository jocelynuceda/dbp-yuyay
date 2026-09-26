package com.yuyay.attachment;

import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.exception.InvalidAttachmentException;
import com.yuyay.attachment.mapper.AttachmentMapper;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.attachment.service.AttachmentService;
import com.yuyay.attachment.storage.FileStorage;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.consultation.repository.ConsultationRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S3 no participa en la transaccion de la base de datos. Si la transaccion termina en rollback
 * despues de subir la foto, el objeto se borra para no dejar archivos huerfanos en el bucket.
 * Se simula el ciclo de la transaccion con TransactionSynchronizationManager: asi se prueba
 * exactamente el callback que Spring invoca al terminar.
 */
class AttachmentStorageRollbackTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    private final AttachmentRepository attachmentRepo = mock(AttachmentRepository.class);
    private final CareSubjectRepository careSubjectRepo = mock(CareSubjectRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);

    private final AttachmentService service = new AttachmentService(
            attachmentRepo, careSubjectRepo, mock(ConsultationRepository.class), userRepo,
            mock(AttachmentMapper.class), fileStorage, currentUserService,
            mock(AuthorizationService.class), mock(ApplicationEventPublisher.class));

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        when(currentUserService.getCurrentUserId()).thenReturn(1L);
        when(careSubjectRepo.findById(7L)).thenReturn(Optional.of(mock(CareSubject.class)));
        when(userRepo.findById(1L)).thenReturn(Optional.of(mock(User.class)));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void deletesUploadedObjectWhenTransactionRollsBack() {
        when(attachmentRepo.save(any(Attachment.class)))
                .thenThrow(new DataIntegrityViolationException("storage_key duplicada"));

        assertThrows(DataIntegrityViolationException.class, () -> service.upload(7L, jpeg(), null));
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        ArgumentCaptor<String> storedKey = ArgumentCaptor.forClass(String.class);
        verify(fileStorage).store(storedKey.capture(), any(byte[].class), eq("image/jpeg"));
        verify(fileStorage).delete(storedKey.getValue());
    }

    @Test
    void keepsObjectWhenTransactionCommits() {
        when(attachmentRepo.save(any(Attachment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(7L, jpeg(), null);
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(fileStorage).store(anyString(), any(byte[].class), eq("image/jpeg"));
        verify(fileStorage, never()).delete(anyString());
    }

    @Test
    void nothingToCleanWhenValidationFailsBeforeUploading() {
        MockMultipartFile fake = new MockMultipartFile("file", "receta.jpg", "image/jpeg", new byte[]{0x4D, 0x5A, 0x00});

        assertThrows(InvalidAttachmentException.class, () -> service.upload(7L, fake, null));
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(fileStorage);
    }

    private MockMultipartFile jpeg() {
        return new MockMultipartFile("file", "receta.jpg", "image/jpeg", JPEG);
    }

    /** Lo que hace Spring al cerrar la transaccion: avisa a cada callback registrado. */
    private void completeTransaction(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
    }
}
