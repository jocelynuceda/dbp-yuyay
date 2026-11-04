package com.yuyay.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Código vigente para restablecer la contraseña: como máximo uno por usuario. */
@Entity
@Table(name = "password_reset_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant sentAt;

    @Column(nullable = false)
    private int attempts;
}
