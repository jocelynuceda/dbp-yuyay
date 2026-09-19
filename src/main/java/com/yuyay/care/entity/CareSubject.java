package com.yuyay.care.entity;

import com.yuyay.audit.entity.AccessLog;
import com.yuyay.consultation.entity.Consultation;
import com.yuyay.consultation.entity.Handoff;
import com.yuyay.health.entity.HealthEntry;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "care_subjects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareSubject {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String name;

    private LocalDate birthDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "careSubject", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<CareRelationship> careRelationships = new ArrayList<>();

    @OneToMany(mappedBy = "careSubject", fetch = FetchType.LAZY)
    @Builder.Default
    private List<HealthEntry> healthEntries = new ArrayList<>();

    @OneToMany(mappedBy = "careSubject", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Consultation> consultations = new ArrayList<>();

    @OneToMany(mappedBy = "careSubject", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Handoff> handoffs = new ArrayList<>();

    @OneToMany(mappedBy = "careSubject", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Delegation> delegations = new ArrayList<>();

    @OneToMany(mappedBy = "careSubject", fetch = FetchType.LAZY)
    @Builder.Default
    private List<AccessLog> accessLogs = new ArrayList<>();

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
