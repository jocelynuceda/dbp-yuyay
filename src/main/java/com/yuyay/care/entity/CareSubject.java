package com.yuyay.care.entity;

import org.hibernate.annotations.ColumnDefault;
import com.yuyay.audit.entity.AccessLog;
import com.yuyay.common.Gender;
import com.yuyay.consultation.entity.Consultation;
import com.yuyay.consultation.entity.Handoff;
import com.yuyay.health.entity.HealthEntry;
import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "care_subjects",
        // Una cuenta es titular de a lo sumo un perfil. En PostgreSQL varios NULL conviven en un
        // índice único, así que los perfiles sin dueño no se estorban.
        uniqueConstraints = @UniqueConstraint(name = "uk_care_subject_account_user", columnNames = "account_user_id")
)
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

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Gender gender;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * La cuenta de la persona a la que pertenece este perfil, cuando existe.
     *
     * <p>Si no es null, ese perfil <b>es</b> esa persona: deja de ser «alguien de quien un cuidador
     * habla» y pasa a tener titular. Se fija durante el registro o al canjear una entrega.
     * Solo se retira de un perfil inicial sin uso para recibir el perfil entregado.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_user_id")
    private User accountUser;

    private Instant claimedAt;

    /** Identifica únicamente perfiles creados al registrarse. Los perfiles anteriores quedan en false. */
    @ColumnDefault("false")
    @Column(nullable = false)
    @Builder.Default
    private boolean registrationProfile = false;

    @ColumnDefault("false")
    @Column(nullable = false)
    @Builder.Default
    private boolean registrationProfileUsed = false;

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
