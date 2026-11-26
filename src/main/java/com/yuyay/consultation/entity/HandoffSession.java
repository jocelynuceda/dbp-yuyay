package com.yuyay.consultation.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "handoff_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HandoffSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "handoff_id", nullable = false)
    private Handoff handoff;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Min(1)
    @Column(nullable = false)
    private Integer sessionWindowMinutes;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean accessCodeRequired;

    @Column(updatable = false)
    private Instant downloadAuthorizedAt;

    @Column(length = 120, updatable = false)
    private String subjectNameSnapshot;

    private Instant firstOpenedAt;

    private Instant revokedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isDownloadAllowed() {
        return downloadAuthorizedAt != null;
    }

    public String displaySubjectName() {
        return subjectNameSnapshot == null ? handoff.getCareSubject().getName() : subjectNameSnapshot;
    }

    public boolean isExpired() {
        return isExpired(Instant.now());
    }

    public boolean isExpired(Instant now) {
        return revokedAt != null || !accessExpiresAt().isAfter(now);
    }

    public Instant accessExpiresAt() {
        if (firstOpenedAt == null) return expiresAt;
        Instant windowEnd = firstOpenedAt.plusSeconds(sessionWindowMinutes * 60L);
        return windowEnd.isBefore(expiresAt) ? windowEnd : expiresAt;
    }
}
