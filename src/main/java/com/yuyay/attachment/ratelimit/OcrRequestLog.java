package com.yuyay.attachment.ratelimit;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;

/** Stores only who requested an OCR and when, never document content. */
@Entity
@Table(
        name = "ocr_request_log",
        indexes = @Index(name = "idx_ocr_request_log_user_time", columnList = "user_id, requested_at")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OcrRequestLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @NotNull
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;
}
