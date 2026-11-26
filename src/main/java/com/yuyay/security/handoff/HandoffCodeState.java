package com.yuyay.security.handoff;

import com.yuyay.consultation.entity.HandoffSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** Datos secretos y límite persistente, separados del modelo/respuestas de consulta. Nunca serializar. */
@Entity
@Table(name = "handoff_code_states")
@Getter
@Setter
@NoArgsConstructor
public class HandoffCodeState {
    @Id
    private Long sessionId;

    @MapsId
    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private HandoffSession session;

    @Column(nullable = false, length = 32)
    private byte[] secret;

    @Column(nullable = false)
    private int failedAttempts;

    private Instant failureWindowStartedAt;

    private Long lastAcceptedStep;
}
