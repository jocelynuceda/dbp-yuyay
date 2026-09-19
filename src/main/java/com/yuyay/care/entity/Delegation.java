package com.yuyay.care.entity;

import com.yuyay.health.entity.HealthCategory;
import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "delegations", indexes = @Index(name = "idx_delegation_subject", columnList = "care_subject_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Delegation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "care_subject_id", nullable = false)
    private CareSubject careSubject;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by", nullable = false)
    private User grantedBy;

    @NotBlank
    @Column(nullable = false, length = 120)
    private String granteeName;

    @Email
    @NotBlank
    @Column(nullable = false, length = 150)
    private String granteeEmail;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @NotNull
    @Column(nullable = false)
    private Instant validUntil;

    private Instant exchangedAt;

    private Instant revokedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "delegation_categories",
            joinColumns = @JoinColumn(name = "delegation_id"),
            inverseJoinColumns = @JoinColumn(name = "health_category_id")
    )
    @Builder.Default
    private Set<HealthCategory> categories = new HashSet<>();

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isValidNow() {
        return revokedAt == null && validUntil.isAfter(Instant.now());
    }
}
