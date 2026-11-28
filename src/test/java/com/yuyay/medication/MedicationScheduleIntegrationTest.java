package com.yuyay.medication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Horario de medicamentos y registro de tomas")
class MedicationScheduleIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Lima"));
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String ana;
    private long subject;
    private long losartan;

    @BeforeEach
    void setUp() throws Exception {
        ana = register("Ana Torres").token();
        subject = send(post("/api/v1/care-subjects"), ana, Map.of(
                "requestId", UUID.randomUUID().toString(),
                "declaration", Map.of("type", "FICTITIOUS", "textVersion", "1", "confirmed", true),
                "name", "Rosa"), 201).get("id").asLong();
        losartan = entry("MEDICATION", "Losartán", "50 mg");
    }

    @Test
    @DisplayName("el horario genera las tomas del día en orden y se pueden marcar")
    void theScheduleBuildsTodaysDoses() throws Exception {
        long other = entry("MEDICATION", "Metformina", "850 mg");
        putSchedule(losartan, List.of("20:00", "08:00"), null, YESTERDAY, null, 200);
        putSchedule(other, List.of("13:00"), null, TODAY.minusDays(3), null, 200);

        JsonNode today = agenda(TODAY, TODAY);
        assertEquals(3, today.size());
        assertEquals(List.of("08:00:00", "13:00:00", "20:00:00"),
                List.of(today.get(0).get("time").asText(), today.get(1).get("time").asText(), today.get(2).get("time").asText()));
        assertEquals("Losartán", today.get(0).get("medication").asText());
        assertEquals("50 mg", today.get(0).get("dose").asText());
        assertEquals("Rosa", today.get(0).get("careSubjectName").asText());
        assertTrue(today.get(0).get("status").isNull());
        assertFalse(today.get(0).get("mine").asBoolean(), "Rosa es alguien a quien Ana cuida");

        // La toma de ayer pasó hace más de 30 min: ya no se marca «tomada», solo «se tomó tarde»
        long scheduleId = today.get(0).get("scheduleId").asLong();
        send(put("/api/v1/medication-schedules/" + scheduleId + "/doses"), ana,
                Map.of("date", YESTERDAY.toString(), "time", "08:00", "status", "TAKEN"), 409);
        JsonNode marked = send(put("/api/v1/medication-schedules/" + scheduleId + "/doses"), ana,
                Map.of("date", YESTERDAY.toString(), "time", "08:00", "status", "LATE"), 200);
        assertEquals("LATE", marked.get("status").asText());
        assertEquals("Ana Torres", marked.get("markedByName").asText());
        assertEquals("LATE", agenda(YESTERDAY, YESTERDAY).get(0).get("status").asText());

        send(delete("/api/v1/medication-schedules/" + scheduleId + "/doses?date=" + YESTERDAY + "&time=08:00"), ana, null, 204);
        assertTrue(agenda(YESTERDAY, YESTERDAY).get(0).get("status").isNull());
    }

    @Test
    @DisplayName("respeta los días de la semana y las fechas de inicio y fin")
    void daysAndDatesLimitTheDoses() throws Exception {
        LocalDate monday = TODAY.with(DayOfWeek.MONDAY);
        putSchedule(losartan, List.of("09:00"), List.of("MONDAY", "WEDNESDAY"), monday, monday.plusDays(9), 200);

        JsonNode twoWeeks = agenda(monday, monday.plusDays(13));
        // Lunes y miércoles de la primera semana, y el lunes y miércoles siguientes (el fin es el miércoles)
        assertEquals(4, twoWeeks.size());
        assertEquals(monday.plusDays(9).toString(), twoWeeks.get(3).get("date").asText());

        putSchedule(losartan, List.of("09:00"), null, monday, monday.minusDays(1), 409);
    }

    @Test
    @DisplayName("solo los medicamentos tienen horario y no se marca una toma que no existe")
    void onlyMedicationsAndScheduledDoses() throws Exception {
        long allergy = entry("ALLERGY", "Penicilina", null);
        putSchedule(allergy, List.of("08:00"), null, TODAY, null, 409);
        putSchedule(losartan, List.of(), null, TODAY, null, 400);

        long scheduleId = putSchedule(losartan, List.of("08:00"), null, TODAY, null, 200).get("id").asLong();
        send(put("/api/v1/medication-schedules/" + scheduleId + "/doses"), ana,
                Map.of("date", TODAY.toString(), "time", "09:00", "status", "TAKEN"), 409);
        send(put("/api/v1/medication-schedules/" + scheduleId + "/doses"), ana,
                Map.of("date", TODAY.plusDays(5).toString(), "time", "08:00", "status", "TAKEN"), 409);
        // Mañana a las 8 siempre falta más de 2 horas: todavía no es hora
        JsonNode tooEarly = send(put("/api/v1/medication-schedules/" + scheduleId + "/doses"), ana,
                Map.of("date", TODAY.plusDays(1).toString(), "time", "08:00", "status", "TAKEN"), 409);
        assertTrue(tooEarly.get("message").asText().contains("2 horas"));
    }

    @Test
    @DisplayName("quien no cuida a la persona no ve ni marca sus tomas")
    void strangersSeeNothing() throws Exception {
        long scheduleId = putSchedule(losartan, List.of("08:00"), null, TODAY, null, 200).get("id").asLong();
        String stranger = register("Luis").token();

        assertEquals(0, send(get("/api/v1/me/medication-agenda?from=" + TODAY), stranger, null, 200).size());
        send(get("/api/v1/care-subjects/" + subject + "/health-entries/" + losartan + "/schedule"), stranger, null, 403);
        send(put("/api/v1/medication-schedules/" + scheduleId + "/doses"), stranger,
                Map.of("date", TODAY.toString(), "time", "08:00", "status", "TAKEN"), 403);
    }

    @Test
    @DisplayName("al eliminar el medicamento sus tomas salen de la agenda, y quitar el horario borra sus marcas")
    void deletingRemovesFromTheAgenda() throws Exception {
        putSchedule(losartan, List.of("08:00"), null, TODAY, null, 200);
        assertEquals(1, send(get("/api/v1/care-subjects/" + subject + "/medication-schedules"), ana, null, 200).size());

        send(delete("/api/v1/care-subjects/" + subject + "/health-entries/" + losartan + "/schedule"), ana, null, 204);
        assertEquals(0, agenda(TODAY, TODAY).size());

        putSchedule(losartan, List.of("08:00"), null, TODAY, null, 200);
        send(delete("/api/v1/care-subjects/" + subject + "/health-entries/" + losartan), ana, null, 204);
        assertEquals(0, agenda(TODAY, TODAY).size());
    }

    @Test
    @DisplayName("las tomas del perfil propio vienen marcadas como mías, y para otro cuidador no")
    void ownProfileDosesAreMine() throws Exception {
        long me = send(get("/api/v1/care-subjects"), ana, null, 200).get(0).get("id").asLong();
        send(post("/api/v1/care-subjects/" + me + "/authorization-declarations"), ana,
                Map.of("type", "SELF", "textVersion", "1", "confirmed", true), 200);
        long subjectBefore = subject;
        subject = me;
        long vitamin = entry("MEDICATION", "Vitamina D", "1000 UI");
        putSchedule(vitamin, List.of("09:00"), null, TODAY, null, 200);
        subject = subjectBefore;
        putSchedule(losartan, List.of("08:00"), null, TODAY, null, 200);

        JsonNode today = agenda(TODAY, TODAY);
        assertEquals("Rosa", today.get(0).get("careSubjectName").asText());
        assertFalse(today.get(0).get("mine").asBoolean());
        assertEquals("Ana Torres", today.get(1).get("careSubjectName").asText());
        assertTrue(today.get(1).get("mine").asBoolean());
    }

    @Test
    @DisplayName("las tomas anteriores a la creación del horario no salen ni se marcan, también al editarlo")
    void dosesBeforeTheScheduleExistedAreLeftOut() throws Exception {
        String url = "/api/v1/care-subjects/" + subject + "/health-entries/" + losartan + "/schedule";
        Map<String, Object> body = Map.of("times", List.of("00:00"), "startDate", YESTERDAY.toString());
        long scheduleId = send(put(url), ana, body, 200).get("id").asLong();

        // Ayer y hoy a las 00:00 ya pasaron cuando se creó: no cuentan como no tomadas
        assertEquals(1, agenda(YESTERDAY, TODAY.plusDays(1)).size());
        assertEquals(TODAY.plusDays(1).toString(), agenda(YESTERDAY, TODAY.plusDays(1)).get(0).get("date").asText());
        send(put("/api/v1/medication-schedules/" + scheduleId + "/doses"), ana,
                Map.of("date", YESTERDAY.toString(), "time", "00:00", "status", "LATE"), 409);

        // Editarlo no mueve la fecha de creación: las tomas que ya existían siguen
        String createdAt = jdbc.queryForObject("select created_at from medication_schedules where id = ?", String.class, scheduleId);
        send(put(url), ana, Map.of("times", List.of("00:00", "12:00"), "startDate", YESTERDAY.toString()), 200);
        assertEquals(createdAt, jdbc.queryForObject("select created_at from medication_schedules where id = ?", String.class, scheduleId));
    }

    @Test
    @DisplayName("la agenda acepta como máximo 42 días")
    void theAgendaRangeIsLimited() throws Exception {
        send(get("/api/v1/me/medication-agenda?from=" + TODAY + "&to=" + TODAY.plusDays(41)), ana, null, 200);
        send(get("/api/v1/me/medication-agenda?from=" + TODAY + "&to=" + TODAY.plusDays(42)), ana, null, 409);
    }

    private long entry(String category, String title, String dose) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("categoryCode", category, "title", title, "confidenceLevel", "CONFIRMED"));
        if (dose != null) body.put("dose", dose);
        return send(post("/api/v1/care-subjects/" + subject + "/health-entries"), ana, body, 201).get("id").asLong();
    }

    private JsonNode putSchedule(long entryId, List<String> times, List<String> days, LocalDate start, LocalDate end,
                                 int expected) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("times", times, "startDate", start.toString()));
        if (days != null) body.put("daysOfWeek", days);
        if (end != null) body.put("endDate", end.toString());
        JsonNode schedule = send(put("/api/v1/care-subjects/" + subject + "/health-entries/" + entryId + "/schedule"), ana, body, expected);
        // Como si el horario existiera desde antes: así las tomas de hoy no dependen de la hora de la prueba
        if (expected == 200) jdbc.update("update medication_schedules set created_at = ? where id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(90))), schedule.get("id").asLong());
        return schedule;
    }

    private JsonNode agenda(LocalDate from, LocalDate to) throws Exception {
        return send(get("/api/v1/me/medication-agenda?from=" + from + "&to=" + to), ana, null, 200);
    }

    private record Session(String token) {}

    private Session register(String name) throws Exception {
        String termsVersion = send(get("/api/v1/auth/terms"), null, null, 200).get("version").asText();
        return new Session(send(post("/api/v1/auth/register"), null, Map.of(
                "email", "med." + UUID.randomUUID() + "@yuyay.app",
                "password", "Secreta123",
                "name", name,
                "termsAccepted", true,
                "termsVersion", termsVersion,
                // El registro contrasta el DNI; en pruebas responde el directorio sintético
                "dni", "12345678",
                "fullName", "Persona Sintetica"), 201).get("accessToken").asText());
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank() ? null : json.readTree(response);
    }
}
