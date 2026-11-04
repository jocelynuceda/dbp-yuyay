package com.yuyay.attachment.repository;

import com.yuyay.attachment.entity.Attachment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByCareSubjectIdOrderByUploadedAtDesc(Long careSubjectId);

    Optional<Attachment> findByIdAndCareSubjectId(Long id, Long careSubjectId);

    /** OCR, reintentos y borrado comparten el bloqueo para impedir escrituras tras eliminar. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attachment a where a.id = :id")
    Optional<Attachment> findForUpdateById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attachment a where a.id = :id and a.careSubject.id = :subjectId")
    Optional<Attachment> findForUpdateByIdAndCareSubjectId(Long id, Long subjectId);
}
