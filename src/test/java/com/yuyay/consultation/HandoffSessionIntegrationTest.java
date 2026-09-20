package com.yuyay.consultation;

import com.yuyay.audit.entity.AccessAction;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.consultation.entity.Handoff;
import com.yuyay.consultation.entity.HandoffSession;
import com.yuyay.consultation.repository.HandoffRepository;
import com.yuyay.consultation.repository.HandoffSessionRepository;
import com.yuyay.consultation.service.HandoffSessionService;
import com.yuyay.event.AccessRecordedEvent;
import com.yuyay.security.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class HandoffSessionIntegrationTest {

    @Autowired
    HandoffSessionService handoffSessionService;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationEvents events;

    @MockitoBean
    HandoffSessionRepository handoffSessionRepository;

    @MockitoBean
    HandoffRepository handoffRepository;

    @Test
    void openingHandoffPublishesAccessRecordedEvent() {

        String rawToken = "token-de-prueba";
        String tokenHash = TokenHasher.sha256(rawToken);

        HandoffSession session = mock(HandoffSession.class);
        Handoff handoff = mock(Handoff.class);
        CareSubject careSubject = mock(CareSubject.class);

        when(handoffSessionRepository.findByTokenHash(tokenHash))
                .thenReturn(Optional.of(session));

        when(session.isExpired()).thenReturn(false);
        when(session.getFirstOpenedAt()).thenReturn(null);
        when(session.getHandoff()).thenReturn(handoff);

        when(handoff.getId()).thenReturn(10L);
        when(handoff.getCareSubject()).thenReturn(careSubject);
        when(handoff.getReason()).thenReturn("Consulta médica");
        when(handoff.getQuestions()).thenReturn("¿Qué tratamiento continuar?");
        when(handoff.getCreatedAt()).thenReturn(Instant.now());
        when(handoff.getItems()).thenReturn(List.of());

        when(careSubject.getId()).thenReturn(1L);
        when(careSubject.getName()).thenReturn("Paciente de prueba");

        when(handoffRepository.findDetailById(10L))
                .thenReturn(Optional.of(handoff));

        handoffSessionService.open(
                rawToken,
                "Rosa Pérez",
                "Enfermera"
        );

        List<AccessRecordedEvent> publishedEvents =
                events.stream(AccessRecordedEvent.class).toList();

        assertEquals(1, publishedEvents.size());

        AccessRecordedEvent event = publishedEvents.get(0);

        assertEquals(1L, event.careSubjectId());
        assertEquals(AccessAction.OPEN_HANDOFF_LINK, event.action());
        assertEquals("HANDOFF", event.targetType());
        assertEquals(10L, event.targetId());
        assertEquals("Rosa Pérez", event.visitorName());
        assertEquals("Enfermera", event.visitorRole());
    }

    @Test
    void expiredHandoffSessionReturns410() throws Exception {

        String rawToken = "token-expirado";
        String tokenHash = TokenHasher.sha256(rawToken);

        HandoffSession expiredSession = HandoffSession.builder()
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().minusSeconds(60))
                .sessionWindowMinutes(30)
                .createdAt(Instant.now().minusSeconds(120))
                .build();

        when(handoffSessionRepository.findByTokenHash(tokenHash))
                .thenReturn(Optional.of(expiredSession));

        mvc.perform(post(
                        "/api/v1/public/handoff-sessions/{token}/open",
                        rawToken
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "visitorName": "Rosa Pérez",
                                  "visitorRole": "Enfermera"
                                }
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.error").value("Gone"))
                .andExpect(jsonPath("$.message")
                        .value("El enlace de Handoff expiró o fue revocado"));
    }
}