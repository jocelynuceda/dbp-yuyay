package com.yuyay.health.entity;

import com.yuyay.attachment.entity.Attachment;
import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "health_entry_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_entry_version", columnNames = {"health_entry_id", "version_number"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthEntryVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "health_entry_id", nullable = false)
    private HealthEntry healthEntry;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChangeType changeType;

    @NotBlank
    @Size(max = 150)
    @Column(nullable = false, length = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Size(max = 60)
    @Column(length = 60)
    private String dose;

    @Size(max = 60)
    @Column(length = 60)
    private String frequency;

    private LocalDate occurredOn;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConfidenceLevel confidenceLevel;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "declared_by", nullable = false)
    private User declaredBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_attachment_id")
    private Attachment sourceAttachment;

    @Column(nullable = false, updatable = false)
    private Instant declaredAt;

    @PrePersist
    void onCreate() {
        this.declaredAt = Instant.now();
    }
}
