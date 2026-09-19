package com.yuyay.care.entity;

import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "care_relationships",
        uniqueConstraints = @UniqueConstraint(name = "uk_care_rel_user_subject", columnNames = {"user_id", "care_subject_id"}),
        indexes = @Index(name = "idx_care_rel_user_status", columnList = "user_id, status")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareRelationship {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "care_subject_id", nullable = false)
    private CareSubject careSubject;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CareRole role;

    @Size(max = 60)
    @Column(length = 60)
    private String relationshipLabel;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CareStatus status;

    @Column(nullable = false)
    private Instant invitedAt;

    private Instant acceptedAt;

    private Instant revokedAt;

    @PrePersist
    void onCreate() {
        if (this.invitedAt == null) this.invitedAt = Instant.now();
        if (this.status == null) this.status = CareStatus.PENDING;
    }
}
