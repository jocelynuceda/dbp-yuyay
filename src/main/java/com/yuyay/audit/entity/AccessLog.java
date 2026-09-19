package com.yuyay.audit.entity;

import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.entity.Delegation;
import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "access_logs", indexes = @Index(name = "idx_access_log_subject_time", columnList = "care_subject_id, occurred_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccessLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "care_subject_id", nullable = false)
    private CareSubject careSubject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delegation_id")
    private Delegation delegation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccessAction action;

    @Column(nullable = false, length = 40)
    private String targetType;

    private Long targetId;

    @Column(length = 120)
    private String visitorName;

    @Column(length = 60)
    private String visitorRole;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    void onCreate() {
        if (this.occurredAt == null) this.occurredAt = Instant.now();
    }
}
