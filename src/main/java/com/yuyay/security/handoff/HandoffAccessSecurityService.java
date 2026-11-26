package com.yuyay.security.handoff;

import com.yuyay.consultation.entity.HandoffSession;
import com.yuyay.consultation.repository.HandoffSessionRepository;
import com.yuyay.exception.HandoffSessionExpiredException;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.security.TokenHasher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class HandoffAccessSecurityService {
    public static final String GRANT_HEADER = "X-Handoff-Access";
    private static final int MAX_FAILURES = 5;
    private static final int FAILURE_WINDOW_SECONDS = 300;
    private final HandoffSessionRepository sessions;
    private final HandoffCodeStateRepository codeStates;
    private final HandoffAccessGrantRepository grants;
    private final HandoffCodeGenerator generator;
    private final Clock clock;
    private final EntityManager entityManager;

    public void initialize(HandoffSession session) {
        if (!session.isAccessCodeRequired()) return;
        HandoffCodeState state = new HandoffCodeState();
        state.setSession(session);
        state.setSecret(generator.newSecret());
        codeStates.save(state);
    }

    @Transactional(readOnly = true)
    public HandoffAccessCodeDTO currentCode(HandoffSession session) {
        boolean codeRequired = session.isAccessCodeRequired();
        Optional<HandoffCodeState> loadedState = codeRequired ? codeStates.findById(session.getId()) : Optional.empty();
        // La lectura del estado puede cruzar el límite del periodo.
        Instant now = clock.instant();
        requireActive(session, now);
        if (!codeRequired) {
            throw new InvalidOperationException("Este enlace no requiere un código de acceso");
        }
        HandoffCodeState state = loadedState
                .orElseThrow(() -> new InvalidOperationException("El código de acceso no está disponible"));
        Instant expiresAt = generator.periodExpiresAt(now);
        if (session.accessExpiresAt().isBefore(expiresAt)) expiresAt = session.accessExpiresAt();
        boolean consumed = isConsumed(state, generator.step(now));
        return new HandoffAccessCodeDTO(consumed ? null : generator.code(state.getSecret(), now), consumed, expiresAt, now,
                HandoffCodeGenerator.PERIOD_SECONDS);
    }

    /**
     * /open y este servicio excluyen sólo HandoffCodeException del rollback: los intentos
     * fallidos se confirman, pero otros errores revierten consumo, grant y primera apertura.
     * Orden fijo de bloqueos: sesión, estado de código. Serializa también la primera apertura.
     */
    @Transactional(noRollbackFor = HandoffCodeException.class)
    public IssuedGrant verifyAndIssue(Long sessionId, String submittedCode) {
        HandoffSession session = sessions.findForUpdateById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Enlace de Handoff no encontrado"));
        // La consulta inicial del token pudo cargar la sesión antes de esperar este bloqueo.
        entityManager.refresh(session, LockModeType.PESSIMISTIC_WRITE);
        boolean codeRequired = session.isAccessCodeRequired();
        Optional<HandoffCodeState> loadedState = codeRequired
                ? codeStates.findForUpdateBySessionId(sessionId) : Optional.empty();
        // La decisión usa el mismo instante para vigencia, intentos y paso tras ambos bloqueos.
        Instant now = clock.instant();
        requireActive(session, now);
        if (!codeRequired) {
            throw new InvalidOperationException("Este enlace no requiere un código de acceso");
        }
        HandoffCodeState state = loadedState
                .orElseThrow(() -> new InvalidOperationException("El código de acceso no está disponible"));
        if (state.getFailureWindowStartedAt() != null
                && !state.getFailureWindowStartedAt().plusSeconds(FAILURE_WINDOW_SECONDS).isAfter(now)) {
            state.setFailedAttempts(0);
            state.setFailureWindowStartedAt(null);
        }
        if (state.getFailedAttempts() >= MAX_FAILURES) {
            throw new HandoffCodeException(HttpStatus.TOO_MANY_REQUESTS, "HANDOFF_CODE_RATE_LIMITED",
                    "Demasiados códigos incorrectos. Espera cinco minutos desde el primer intento y vuelve a probar");
        }
        if (submittedCode == null || submittedCode.isBlank()) {
            throw new HandoffCodeException(HttpStatus.FORBIDDEN, "HANDOFF_CODE_REQUIRED",
                    "Pide al cuidador el código de seis dígitos que aparece junto al enlace");
        }
        if (!generator.matches(state.getSecret(), submittedCode, now)) {
            if (state.getFailureWindowStartedAt() == null) state.setFailureWindowStartedAt(now);
            state.setFailedAttempts(state.getFailedAttempts() + 1);
            throw new HandoffCodeException(HttpStatus.FORBIDDEN, "HANDOFF_CODE_INVALID",
                    "Código incorrecto o vencido. Pide el código actual al cuidador");
        }
        long currentStep = generator.step(now);
        if (isConsumed(state, currentStep)) {
            throw new HandoffCodeException(HttpStatus.FORBIDDEN, "HANDOFF_CODE_USED",
                    "Este código ya fue utilizado. Espera el siguiente código y vuelve a intentarlo");
        }
        state.setLastAcceptedStep(currentStep);
        // Un acierto no reinicia el límite: no se permite eludirlo alternando aciertos y errores.
        if (session.getFirstOpenedAt() == null) session.setFirstOpenedAt(now);
        String rawGrant = TokenHasher.randomToken();
        HandoffAccessGrant grant = new HandoffAccessGrant();
        grant.setSession(session);
        grant.setTokenHash(TokenHasher.sha256(rawGrant));
        grant.setExpiresAt(session.accessExpiresAt());
        grants.save(grant);
        return new IssuedGrant(rawGrant, grant.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public void requireGrant(HandoffSession session, String rawGrant) {
        Instant now = clock.instant();
        requireActive(session, now);
        if (!session.isAccessCodeRequired()) return;
        if (rawGrant == null || rawGrant.isBlank()) {
            throw new HandoffCodeException(HttpStatus.FORBIDDEN, "HANDOFF_ACCESS_GRANT_REQUIRED",
                    "Abre el resumen con el código de acceso antes de consultar el documento");
        }
        if (rawGrant.length() > 128 || !grants.existsBySessionIdAndTokenHashAndExpiresAtAfter(
                session.getId(), TokenHasher.sha256(rawGrant), now)) {
            throw new HandoffCodeException(HttpStatus.FORBIDDEN, "HANDOFF_ACCESS_GRANT_INVALID",
                    "El permiso para consultar el documento no es válido. Abre de nuevo el resumen");
        }
    }

    private void requireActive(HandoffSession session, Instant now) {
        if (session.isExpired(now)) {
            throw new HandoffSessionExpiredException("El enlace de Handoff expiró o fue revocado");
        }
    }

    private boolean isConsumed(HandoffCodeState state, long currentStep) {
        // No volver a aceptar pasos anteriores si el reloj del servidor retrocede.
        return state.getLastAcceptedStep() != null && currentStep <= state.getLastAcceptedStep();
    }

    public record IssuedGrant(String token, Instant expiresAt) {}
}
