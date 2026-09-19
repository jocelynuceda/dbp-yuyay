package com.yuyay.health.entity;

import com.yuyay.care.entity.CareSubject;
import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "health_entries", indexes = @Index(name = "idx_health_entry_subject", columnList = "care_subject_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "care_subject_id", nullable = false)
    private CareSubject careSubject;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "health_category_id", nullable = false)
    private HealthCategory category;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant deletedAt;

    @OneToMany(mappedBy = "healthEntry", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("versionNumber DESC")
    @Builder.Default
    private List<HealthEntryVersion> versions = new ArrayList<>();

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
