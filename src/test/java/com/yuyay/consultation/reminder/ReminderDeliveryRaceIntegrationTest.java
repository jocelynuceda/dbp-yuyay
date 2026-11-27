package com.yuyay.consultation.reminder;

import com.yuyay.notification.email.EmailSender;
import com.yuyay.notification.entity.NotificationType;
import com.yuyay.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Entre armar la lista de destinatarios y escribir cada aviso pueden cambiar las cosas: el acceso se
 * revoca o la cita se reprograma. La entrega vuelve a comprobarlo y descarta el aviso preparado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReminderDeliveryRaceIntegrationTest {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final ReminderSchedule SCHEDULE = new ReminderSchedule(LIMA, LocalTime.of(18, 0));
    private static final Instant NOW = LocalDateTime.parse("2031-07-09T18:00").atZone(LIMA).toInstant();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ReminderDelivery delivery;
    @Autowired NotificationRepository notifications;
    @MockitoBean EmailSender emailSender;

    private String anaToken;
    private long anaId;
    private long brunoId;
    private long brunoRelationshipId;
    private long subjectId;

    @BeforeEach
    void setUp() throws Exception {
        String brunoEmail = "bruno." + UUID.randomUUID() + "@yuyay.app";
        JsonNode ana = register("ana." + UUID.randomUUID() + "@yuyay.app", "Ana");
        JsonNode bruno = register(brunoEmail, "Bruno");
        anaToken = ana.get("accessToken").asText();
        anaId = ana.get("user").get("id").asLong();
        brunoId = bruno.get("user").get("id").asLong();
        subjectId = send(post("/api/v1/care-subjects"), anaToken,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Pedro\"}", 201).get("id").asLong();
        brunoRelationshipId = send(post("/api/v1/care-subjects/" + subjectId + "/relationships"), anaToken,
                "{\"email\":\"" + brunoEmail + "\",\"role\":\"CAREGIVER\"}", 201).get("id").asLong();
        send(post("/api/v1/me/relationships/" + brunoRelationshipId + "/accept"), bruno.get("accessToken").asText(), "{}", 200);
    }

    @Test
    void unCuidadorRevocadoDespuesDeArmarLaListaNoRecibeElAviso() throws Exception {
        long consultationId = createConsultation("2031-07-10", "10:00");
        DueReminder forBruno = dueFor(consultationId, brunoId);

        send(delete("/api/v1/care-subjects/" + subjectId + "/relationships/" + brunoRelationshipId), anaToken, null, 204);

        assertThat(delivery.deliverInApp(forBruno, NOW, "Cita de Pedro mañana", "Mañana a las 10:00: Control")).isFalse();
        assertThat(delivery.claimEmail(forBruno, NOW, 3)).isEmpty();
        assertThat(notifications.findByUserIdOrderByCreatedAtDesc(brunoId))
                .noneMatch(n -> n.getType() == NotificationType.APPOINTMENT_REMINDER);
    }

    @Test
    void unaCitaReprogramadaDespuesDeArmarLaListaNoUsaElAvisoViejo() throws Exception {
        long consultationId = createConsultation("2031-07-10", "10:00");
        DueReminder forAna = dueFor(consultationId, anaId);

        send(patch("/api/v1/consultations/" + consultationId), anaToken, "{\"time\":\"16:00\"}", 200);

        assertThat(delivery.deliverInApp(forAna, NOW, "Cita de Pedro mañana", "Mañana a las 10:00: Control")).isFalse();
        assertThat(delivery.claimEmail(forAna, NOW, 3)).isEmpty();
        // La siguiente pasada arma el aviso con la hora nueva
        List<DueReminder> fresh = delivery.findDue(SCHEDULE, NOW).stream()
                .filter(d -> d.consultationId() == consultationId && d.userId() == anaId).toList();
        assertThat(fresh).singleElement().satisfies(d -> assertThat(d.time()).isEqualTo(LocalTime.of(16, 0)));
        assertThat(delivery.deliverInApp(fresh.get(0), NOW, "Cita de Pedro mañana", "Mañana a las 16:00: Control")).isTrue();
    }

    private DueReminder dueFor(long consultationId, long userId) {
        return delivery.findDue(SCHEDULE, NOW).stream()
                .filter(d -> d.consultationId() == consultationId && d.userId() == userId)
                .findFirst().orElseThrow();
    }

    private long createConsultation(String date, String time) throws Exception {
        return send(post("/api/v1/care-subjects/" + subjectId + "/consultations"), anaToken,
                "{\"date\":\"" + date + "\",\"time\":\"" + time + "\",\"reason\":\"Control\"}", 201).get("id").asLong();
    }

    private JsonNode register(String email, String name) throws Exception {
        return send(post("/api/v1/auth/register"), null,
                "{\"email\":\"" + email + "\",\"termsAccepted\":true,\"termsVersion\":\"1.1\",\"password\":\"Secreta123\",\"name\":\"" + name + "\"}", 201);
    }

    private JsonNode send(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : json.readTree(response);
    }
}
