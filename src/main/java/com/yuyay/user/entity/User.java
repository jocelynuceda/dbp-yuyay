package com.yuyay.user.entity;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.common.Gender;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Email
    @NotBlank
    @Column(nullable = false, unique = true, length = 150)
    private String email;

    private String passwordHash;

    @Column(length = 255, updatable = false)
    private String googleSubject;

    @Column(length = 32, updatable = false)
    private String termsVersion;

    @Column(updatable = false)
    private Instant termsAcceptedAt;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String name;

    /** Nombre declarado y contrastado al crear la cuenta. El alias se guarda en {@code name}. */
    @Column(length = 200, updatable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Gender gender;

    /** Opcional. Con ella se muestra la edad en el perfil y el avatar automático cambia con la edad. */
    private LocalDate birthDate;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Cuándo se eliminó la cuenta. La fila sobrevive como lápida porque catorce entidades la
     * referencian, entre ellas la cadena de auditoría del consentimiento y de las versiones
     * clínicas: borrarla se llevaría el historial de otras personas. El dato identificante sí se
     * borra, así que esto no se puede deshacer.
     */
    private Instant deletedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<CareRelationship> careRelationships = new ArrayList<>();

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean hasPasswordCredential() {
        return googleSubject == null && passwordHash != null && !passwordHash.isBlank();
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.role == null) this.role = Role.USER;
    }
}
