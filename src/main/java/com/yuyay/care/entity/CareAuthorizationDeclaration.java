package com.yuyay.care.entity;

import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(
        name = "care_authorization_declarations",
        uniqueConstraints = @UniqueConstraint(name = "uk_care_auth_creation_request",
                columnNames = {"declared_by_user_id", "creation_request_id"}),
        indexes = @Index(name = "idx_care_auth_declaration_subject", columnList = "care_subject_id, id")
)
@Check(constraints = "declaration_type <> 'PENDING'")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CareAuthorizationDeclaration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "care_subject_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_care_auth_declaration_subject"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CareSubject careSubject;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "declared_by_user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_care_auth_declaration_user"))
    private User declaredBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "declaration_type", nullable = false, length = 32)
    private CareAuthorizationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CareAuthorizationScope scope;

    @Column(nullable = false, length = 32)
    private String textVersion;

    @Column(name = "declaration_text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(nullable = false)
    private Instant declaredAt;

    @Column(length = 64)
    private String creationRequestId;

    @Column(length = 64)
    private String creationPayloadHash;
}
