package com.yuyay.security.handoff;

import com.yuyay.consultation.entity.HandoffSession;
import com.yuyay.consultation.repository.HandoffSessionRepository;
import com.yuyay.exception.HandoffSessionExpiredException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandoffAccessSecurityServiceTest {
    private static final long SESSION_ID = 1L;
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant BOUNDARY = START.plusSeconds(30);
    private static final byte[] SECRET = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);

    private final AtomicReference<Instant> now = new AtomicReference<>(BOUNDARY.minusMillis(1));
    private final Clock clock = mock(Clock.class);
    private final HandoffSessionRepository sessions = mock(HandoffSessionRepository.class);
    private final HandoffCodeStateRepository codeStates = mock(HandoffCodeStateRepository.class);
    private final HandoffAccessGrantRepository grants = mock(HandoffAccessGrantRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final HandoffCodeGenerator generator = new HandoffCodeGenerator();
    private final HandoffAccessSecurityService service =
            new HandoffAccessSecurityService(sessions, codeStates, grants, generator, clock, entityManager);

    private final HandoffSession session = HandoffSession.builder().id(SESSION_ID).accessCodeRequired(true)
            .expiresAt(START.plusSeconds(3600)).sessionWindowMinutes(15).build();
    private final HandoffCodeState state = new HandoffCodeState();

    @BeforeEach
    void setUp() {
        state.setSecret(SECRET);
        when(clock.instant()).thenAnswer(invocation -> now.get());
    }

    @Test
    void currentCodeUsesTheNewStepWhenTheStateReadCrossesTheBoundary() {
        state.setLastAcceptedStep(generator.step(START));
        when(codeStates.findById(SESSION_ID)).thenAnswer(invocation -> readStateAfterBoundary());

        HandoffAccessCodeDTO result = service.currentCode(session);

        assertEquals(generator.code(SECRET, BOUNDARY), result.code());
        assertFalse(result.consumed());
        assertEquals(BOUNDARY, result.serverTime());
        assertEquals(BOUNDARY.plusSeconds(30), result.expiresAt());
    }

    @Test
    void currentCodeRejectsALinkThatExpiresDuringTheStateRead() {
        session.setExpiresAt(BOUNDARY);
        when(codeStates.findById(SESSION_ID)).thenAnswer(invocation -> readStateAfterBoundary());

        assertThrows(HandoffSessionExpiredException.class, () -> service.currentCode(session));
    }

    @Test
    void verificationUsesTheNewStepAndResetsTheFailureWindowAtTheBoundary() {
        waitForStateLockAcrossBoundary();
        state.setFailedAttempts(5);
        state.setFailureWindowStartedAt(BOUNDARY.minusSeconds(300));

        var issued = service.verifyAndIssue(SESSION_ID, generator.code(SECRET, BOUNDARY), "Visitante", "Médica");

        assertEquals(generator.step(BOUNDARY), state.getLastAcceptedStep());
        assertEquals(0, state.getFailedAttempts());
        assertNull(state.getFailureWindowStartedAt());
        assertEquals(BOUNDARY, session.getFirstOpenedAt());
        assertEquals(BOUNDARY.plusSeconds(900), issued.expiresAt());
        var order = inOrder(sessions, entityManager, codeStates, clock);
        order.verify(sessions).findForUpdateById(SESSION_ID);
        order.verify(entityManager).refresh(session, LockModeType.PESSIMISTIC_WRITE);
        order.verify(codeStates).findForUpdateBySessionId(SESSION_ID);
        order.verify(clock).instant();
    }

    @ParameterizedTest
    @ValueSource(ints = {-30, 30})
    void verificationRejectsAdjacentStepsAfterTheBoundary(int offsetSeconds) {
        waitForStateLockAcrossBoundary();
        String code = generator.code(SECRET, BOUNDARY.plusSeconds(offsetSeconds));

        var error = assertThrows(HandoffCodeException.class,
                () -> service.verifyAndIssue(SESSION_ID, code, "Visitante", "Médica"));

        assertEquals("HANDOFF_CODE_INVALID", error.getCode());
        assertEquals(1, state.getFailedAttempts());
        assertEquals(BOUNDARY, state.getFailureWindowStartedAt());
        assertNull(state.getLastAcceptedStep());
        assertNull(session.getFirstOpenedAt());
        verifyNoInteractions(grants);
    }

    @Test
    void verificationRejectsALinkThatExpiresWhileWaitingForTheStateLock() {
        waitForStateLockAcrossBoundary();
        session.setExpiresAt(BOUNDARY);

        assertThrows(HandoffSessionExpiredException.class,
                () -> service.verifyAndIssue(SESSION_ID, generator.code(SECRET, START), "Visitante", "Médica"));

        assertEquals(0, state.getFailedAttempts());
        assertNull(state.getLastAcceptedStep());
        assertNull(session.getFirstOpenedAt());
        verifyNoInteractions(grants);
    }

    private void waitForStateLockAcrossBoundary() {
        when(sessions.findForUpdateById(SESSION_ID)).thenReturn(Optional.of(session));
        when(codeStates.findForUpdateBySessionId(SESSION_ID)).thenAnswer(invocation -> readStateAfterBoundary());
    }

    private Optional<HandoffCodeState> readStateAfterBoundary() {
        now.set(BOUNDARY);
        return Optional.of(state);
    }
}
