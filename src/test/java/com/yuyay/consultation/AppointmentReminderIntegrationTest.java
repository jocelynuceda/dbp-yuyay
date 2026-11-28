package com.yuyay.consultation;

import com.yuyay.consultation.reminder.AppointmentReminderRepository;
import com.yuyay.consultation.reminder.AppointmentReminderService;
import com.yuyay.consultation.reminder.ReminderChannel;
import com.yuyay.consultation.reminder.ReminderStatus;
import com.yuyay.notification.email.EmailSender;
import com.yuyay.notification.entity.Notification;
import com.yuyay.notification.entity.NotificationType;
import com.yuyay.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recordatorios de citas desde el servidor (issue #71): un aviso el día anterior a las 18:00 (hora de
 * Lima) en la app y por correo a cada cuidador activo, sin duplicados, con reloj controlado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppointmentReminderIntegrationTest {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    // Fechas lejanas para no cruzarse con citas de otras pruebas que comparten la base en memoria
    private static final String DAY = "2031-03-14";
    private static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2031-01-01T00:00:00Z"));

    @TestConfiguration
    static class TestClock {
        @Bean @Primary
        Clock reminderTestClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return NOW.get(); }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppointmentReminderService reminders;
    @Autowired AppointmentReminderRepository reminderRepository;
    @Autowired NotificationRepository notifications;
    @MockitoBean EmailSender emailSender;

    private String anaToken;
    private long anaId;
    private String anaEmail;
    private long brunoId;
    private String brunoEmail;
    private long carlaId;
    private long brunoRelationshipId;
    private long subjectId;

    @BeforeEach
    void setUp() throws Exception {
        anaEmail = "ana." + UUID.randomUUID() + "@yuyay.app";
        brunoEmail = "bruno." + UUID.randomUUID() + "@yuyay.app";
        String carlaEmail = "carla." + UUID.randomUUID() + "@yuyay.app";
        JsonNode ana = register(anaEmail, "Ana");
        JsonNode bruno = register(brunoEmail, "Bruno");
        JsonNode carla = register(carlaEmail, "Carla");
        anaToken = ana.get("accessToken").asText();
        anaId = ana.get("user").get("id").asLong();
        brunoId = bruno.get("user").get("id").asLong();
        carlaId = carla.get("user").get("id").asLong();

        subjectId = send(post("/api/v1/care-subjects"), anaToken,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Pedro\"}", 201).get("id").asLong();
        brunoRelationshipId = send(post("/api/v1/care-subjects/" + subjectId + "/relationships"), anaToken,
                "{\"email\":\"" + brunoEmail + "\",\"role\":\"CAREGIVER\"}", 201).get("id").asLong();
        send(post("/api/v1/me/relationships/" + brunoRelationshipId + "/accept"), bruno.get("accessToken").asText(), "{}", 200);
        // Carla queda invitada sin aceptar: no es cuidadora activa
        send(post("/api/v1/care-subjects/" + subjectId + "/relationships"), anaToken,
                "{\"email\":\"" + carlaEmail + "\",\"role\":\"CAREGIVER\"}", 201);
        clearInvocations(emailSender);
    }

    @Test
    void avisaElDiaAnteriorALas18EnLaAppYPorCorreoSinDuplicar() throws Exception {
        long consultationId = createConsultation(DAY, "\"10:00\"", "Control de presión");

        at("2031-03-13T17:59");
        reminders.sendDue();
        assertThat(reminderNotifications(anaId)).isEmpty();
        verify(emailSender, never()).send(eq(anaEmail), anyString(), anyString());

        at("2031-03-13T18:00");
        reminders.sendDue();
        List<Notification> ana = reminderNotifications(anaId);
        assertThat(ana).singleElement().satisfies(n -> {
            assertThat(n.getTitle()).isEqualTo("Cita de Pedro mañana");
            assertThat(n.getBody()).isEqualTo("Mañana a las 10:00: Control de presión");
            assertThat(n.getCareSubjectId()).isEqualTo(subjectId);
        });
        assertThat(reminderNotifications(brunoId)).hasSize(1);
        assertThat(reminderNotifications(carlaId)).isEmpty();

        var html = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(anaEmail), eq("Yuyay · recordatorio de cita"), html.capture());
        assertThat(html.getValue()).contains("Pedro", "mañana a las 10:00", "/people/" + subjectId + "?tab=appointments")
                .doesNotContain("Control de presión");
        verify(emailSender).send(eq(brunoEmail), anyString(), anyString());

        // Repetir la pasada (o reiniciar el servidor) no duplica nada
        at("2031-03-13T18:05");
        reminders.sendDue();
        assertThat(reminderNotifications(anaId)).hasSize(1);
        verify(emailSender, times(1)).send(eq(anaEmail), anyString(), anyString());

        // Al reprogramarla se vuelve a avisar para la nueva fecha, no antes
        send(patch("/api/v1/consultations/" + consultationId), anaToken, "{\"date\":\"2031-03-15\"}", 200);
        reminders.sendDue();
        assertThat(reminderNotifications(anaId)).hasSize(1);
        at("2031-03-14T18:00");
        reminders.sendDue();
        assertThat(reminderNotifications(anaId)).hasSize(2);
        verify(emailSender, times(2)).send(eq(anaEmail), anyString(), anyString());

        // Una cita con avisos enviados se puede borrar
        send(delete("/api/v1/consultations/" + consultationId), anaToken, null, 204);
        assertThat(reminderRepository.findAll()).noneMatch(r -> r.getConsultation().getId().equals(consultationId));
    }

    @Test
    void siElServidorEstuvoApagadoAvisaMientrasLaCitaNoHayaEmpezado() throws Exception {
        createConsultation("2031-04-10", null, "Vacuna");
        createConsultation("2031-04-10", "\"08:00\"", "Análisis");

        at("2031-04-10T09:00");
        reminders.sendDue();
        // La cita sin hora sigue vigente todo el día; la de las 08:00 ya pasó y no se avisa
        assertThat(reminderNotifications(anaId)).singleElement().satisfies(n -> {
            assertThat(n.getTitle()).isEqualTo("Cita de Pedro hoy");
            assertThat(n.getBody()).isEqualTo("Hoy (sin hora registrada): Vacuna");
        });

        at("2031-04-11T00:00");
        reminders.sendDue();
        assertThat(reminderNotifications(anaId)).hasSize(1);
    }

    @Test
    void quienPierdeElAccesoYaNoRecibeElAviso() throws Exception {
        createConsultation("2031-05-20", "\"11:30\"", "Dentista");
        send(delete("/api/v1/care-subjects/" + subjectId + "/relationships/" + brunoRelationshipId), anaToken, null, 204);

        at("2031-05-19T18:00");
        reminders.sendDue();
        assertThat(reminderNotifications(anaId)).hasSize(1);
        assertThat(reminderNotifications(brunoId)).isEmpty();
        verify(emailSender, never()).send(eq(brunoEmail), anyString(), anyString());
    }

    @Test
    void unCorreoFallidoSeReintentaHastaTresVecesSinRepetirElAvisoEnLaApp() throws Exception {
        long consultationId = createConsultation("2031-06-02", "\"15:00\"", "Control");
        doThrow(new IllegalStateException("correo caído")).when(emailSender).send(eq(anaEmail), anyString(), anyString());

        at("2031-06-01T18:00");
        for (int i = 0; i < 4; i++) reminders.sendDue();
        verify(emailSender, times(3)).send(eq(anaEmail), anyString(), anyString());
        assertThat(reminderNotifications(anaId)).hasSize(1);
        assertThat(emailReminder(consultationId, anaId).getStatus()).isEqualTo(ReminderStatus.FAILED);

        // Un nuevo fallo transitorio que se recupera antes del tercer intento termina enviado
        long other = createConsultation("2031-06-03", "\"15:00\"", "Control");
        doThrow(new IllegalStateException("correo caído")).doNothing().when(emailSender).send(eq(anaEmail), anyString(), anyString());
        at("2031-06-02T18:00");
        reminders.sendDue();
        reminders.sendDue();
        assertThat(emailReminder(other, anaId).getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(emailReminder(other, anaId).getAttempts()).isEqualTo(2);
        doNothing().when(emailSender).send(anyString(), anyString(), anyString());
    }

    private com.yuyay.consultation.reminder.AppointmentReminder emailReminder(long consultationId, long userId) {
        return reminderRepository.findAll().stream()
                .filter(r -> r.getConsultation().getId().equals(consultationId) && r.getUser().getId().equals(userId)
                        && r.getChannel() == ReminderChannel.EMAIL)
                .findFirst().orElseThrow();
    }

    private List<Notification> reminderNotifications(long userId) {
        return notifications.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(n -> n.getType() == NotificationType.APPOINTMENT_REMINDER)
                .toList();
    }

    private long createConsultation(String date, String time, String reason) throws Exception {
        String timeField = time == null ? "" : ",\"time\":" + time;
        return send(post("/api/v1/care-subjects/" + subjectId + "/consultations"), anaToken,
                "{\"date\":\"" + date + "\"" + timeField + ",\"reason\":\"" + reason + "\"}", 201).get("id").asLong();
    }

    private static void at(String limaDateTime) {
        NOW.set(LocalDateTime.parse(limaDateTime).atZone(LIMA).toInstant());
    }

    private JsonNode register(String email, String name) throws Exception {
        return send(post("/api/v1/auth/register"), null,
                "{\"email\":\"" + email + "\",\"termsAccepted\":true,\"termsVersion\":\"1.3\",\"fullName\":\"Persona Sintetica\",\"dni\":\"12345678\",\"password\":\"Secreta123\",\"name\":\"" + name + "\"}", 201);
    }

    private JsonNode send(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : json.readTree(response);
    }
}
