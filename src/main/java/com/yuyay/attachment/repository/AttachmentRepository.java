package com.yuyay.attachment.repository;

import com.yuyay.attachment.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByCareSubjectIdOrderByUploadedAtDesc(Long careSubjectId);

    Optional<Attachment> findByIdAndCareSubjectId(Long id, Long careSubjectId);
}
