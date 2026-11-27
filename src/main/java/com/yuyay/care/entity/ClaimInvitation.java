package com.yuyay.care.entity;

import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;

/**
 * Invitación para entregar un perfil a la persona de la que habla (§3.4 del análisis).
 *
 * <p>Sigue el patrón ya probado de {@link Delegation}: token aleatorio que solo existe en el enlace,
 * hash en la base, vigencia y canje único. No se empareja por correo a secas porque la app no
 * acredita identidad, y un error de identidad sobre datos de salud no se deshace.
 */
@Entity
@Table(
        name = "claim_invitations",
        indexes = @Index(name = "idx_claim_invitation_subject", columnList = "care_subject_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClaimInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "care_subject_id", nullable = false)
    private CareSubject careSubject;

    /** Quien entrega el perfil. Se conserva aunque después quede como cuidador. */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Email
    @NotBlank
    @Column(nullable = false, length = 150)
    private String invitedEmail;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @NotNull
    @Column(nullable = false)
    private Instant validUntil;

    private Instant exchangedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exchanged_by")
    private User exchangedBy;

    private Instant revokedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isValidNow() {
        return revokedAt == null && validUntil.isAfter(Instant.now());
    }

    public boolean isPending() {
        return exchangedAt == null && isValidNow();
    }
}
