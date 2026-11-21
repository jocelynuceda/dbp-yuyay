package com.yuyay.attachment.entity;

import com.yuyay.care.entity.CareSubject;
import com.yuyay.consultation.entity.Consultation;
import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "attachments",
        uniqueConstraints = @UniqueConstraint(name = "uk_attachment_storage_key", columnNames = "storage_key"),
        indexes = @Index(name = "idx_attachment_subject", columnList = "care_subject_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Attachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "care_subject_id", nullable = false)
    private CareSubject careSubject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id")
    private Consultation consultation;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String originalFilename;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String contentType;

    @NotNull
    @Positive
    @Column(nullable = false)
    private Long sizeBytes;

    @NotBlank
    @Size(max = 255)
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OcrStatus ocrStatus;

    @Column(columnDefinition = "TEXT")
    private String ocrText;

    @Size(max = 500)
    @Column(length = 500)
    private String ocrError;

    @Column(nullable = false, updatable = false)
    private Instant uploadedAt;

    private Instant ocrCompletedAt;

    /**
     * When the last OCR of this document was requested (upload or retry); used by the OCR rate limit.
     * Null in rows created before this field existed.
     */
    @Column(name = "ocr_requested_at")
    private Instant ocrRequestedAt;

    /** Who asked for the last OCR (upload or retry); may differ from the uploader. Plain id, no relation. */
    @Column(name = "ocr_requested_by_id")
    private Long ocrRequestedById;

    /**
     * Lowercase name of {@link UploadSource} (camera, gallery, files, web) or null. What the client says it used;
     * informational, not trusted. Stored as text on purpose so new values never need a schema change.
     */
    @Column(name = "upload_source", length = 16)
    private String uploadSource;

    /**
     * Sanitized value of the X-Yuyay-Client header (web, android/ios app and build) or null when absent or invalid.
     * Sent by the client; informational, never trusted.
     */
    @Column(name = "upload_client", length = 64)
    private String uploadClient;

    /** Width in pixels, measured by the server from the image header; null for PDF/audio or when unreadable. */
    @Column(name = "image_width")
    private Integer imageWidth;

    /** Height in pixels, measured by the server from the image header; null for PDF/audio or when unreadable. */
    @Column(name = "image_height")
    private Integer imageHeight;

    @PrePersist
    void onCreate() {
        this.uploadedAt = Instant.now();
        if (this.ocrStatus == null) this.ocrStatus = OcrStatus.PENDING;
    }
}
