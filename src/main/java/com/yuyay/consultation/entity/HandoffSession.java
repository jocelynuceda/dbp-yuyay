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

    private Instant firstOpenedAt;

    private Instant revokedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isExpired() {
        Instant now = Instant.now();
        if (revokedAt != null || expiresAt.isBefore(now)) return true;
        return firstOpenedAt != null && firstOpenedAt.plusSeconds(sessionWindowMinutes * 60L).isBefore(now);
    }
}
