package com.yuyay.user.entity;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.common.Gender;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;
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

    /** Nombre completo del documento; {@code name} es el nombre de usuario. Nulo en cuentas anteriores. */
    @Column(length = 200, updatable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Gender gender;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<CareRelationship> careRelationships = new ArrayList<>();

    public boolean hasPasswordCredential() {
        return googleSubject == null && passwordHash != null && !passwordHash.isBlank();
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.role == null) this.role = Role.USER;
    }
}
