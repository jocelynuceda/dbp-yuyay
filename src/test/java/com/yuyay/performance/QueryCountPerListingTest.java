package com.yuyay.performance;

import com.yuyay.notification.entity.Notification;
import com.yuyay.notification.entity.NotificationType;
import com.yuyay.notification.repository.NotificationRepository;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba controlada de N+1: el mismo listado con 3 y con 15 elementos debe ejecutar la misma
 * cantidad de sentencias SQL. Si crece con cada elemento, hay una consulta por fila (N+1).
 * Usa su propia base H2 con estadísticas de Hibernate; antes y después de cada petición espera a
 * que terminen los listeners asíncronos (bitácora, avisos) para no mezclar sentencias de otras tareas.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.url=jdbc:h2:mem:yuyay-queries;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryCountPerListingTest {

    private static final int FEW = 3;
    private static final int MANY = 15;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired UserRepository users;
    @Autowired NotificationRepository notifications;

    @Test
    void myPeopleList() throws Exception {
        assertConstant("GET /care-subjects", n -> {
            String token = register();
            for (int i = 0; i < n; i++) createSubject(token, "Persona " + i);
            return new Request("/api/v1/care-subjects", token);
        });
    }

    @Test
    void healthEntriesPage() throws Exception {
        assertConstant("GET /care-subjects/{id}/health-entries", n -> {
            String token = register();
            long subjectId = createSubject(token, "Rosa");
            for (int i = 0; i < n; i++) {
                postJson(token, "/api/v1/care-subjects/" + subjectId + "/health-entries",
                        "{\"categoryCode\":\"MEDICATION\",\"title\":\"Medicamento " + i + "\",\"confidenceLevel\":\"CONFIRMED\"}");
            }
            return new Request("/api/v1/care-subjects/" + subjectId + "/health-entries?size=50", token);
        });
    }

    @Test
    void consultationsList() throws Exception {
        assertConstant("GET /care-subjects/{id}/consultations", n -> {
            String token = register();
            long subjectId = createSubject(token, "Pedro");
            for (int i = 0; i < n; i++) {
                postJson(token, "/api/v1/care-subjects/" + subjectId + "/consultations",
                        "{\"date\":\"2026-10-" + String.format("%02d", i + 1) + "\",\"reason\":\"Control " + i + "\"}");
            }
            return new Request("/api/v1/care-subjects/" + subjectId + "/consultations", token);
        });
    }

    @Test
    void caregiversList() throws Exception {
        assertConstant("GET /care-subjects/{id}/relationships", n -> {
            String token = register();
            long subjectId = createSubject(token, "Lucía");
            for (int i = 0; i < n; i++) {
                String email = "cuidador." + UUID.randomUUID() + "@yuyay.app";
                registerAs(email);
                postJson(token, "/api/v1/care-subjects/" + subjectId + "/relationships",
                        "{\"email\":\"" + email + "\",\"role\":\"CAREGIVER\"}");
            }
            return new Request("/api/v1/care-subjects/" + subjectId + "/relationships", token);
        });
    }

    @Test
    void notificationsPage() throws Exception {
        assertConstant("GET /notifications", n -> {
            String email = "owner." + UUID.randomUUID() + "@yuyay.app";
            String token = registerAs(email);
            User owner = users.findByEmailIgnoreCase(email).orElseThrow();
            for (int i = 0; i < n; i++) {
                notifications.save(Notification.builder()
                        .user(owner).type(NotificationType.ENTRY_CHANGED)
                        .careSubjectId(1L).title("Aviso " + i).body("Detalle").build());
            }
            return new Request("/api/v1/notifications?size=50", token);
        });
    }

    // ---------------------------------------------------------------------

    private record Request(String path, String token) {}

    @FunctionalInterface
    private interface Scenario {
        Request build(int elements) throws Exception;
    }

    private void assertConstant(String label, Scenario scenario) throws Exception {
        long few = statementsFor(scenario.build(FEW));
        long many = statementsFor(scenario.build(MANY));
        System.out.printf("[consultas SQL] %s: %d elementos -> %d, %d elementos -> %d%n", label, FEW, few, MANY, many);
        assertEquals(few, many, "%s: con %d elementos hace %d sentencias y con %d hace %d (posible N+1)"
                .formatted(label, FEW, few, MANY, many));
    }

    private long statementsFor(Request request) throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        Thread.sleep(300); // que terminen los listeners asíncronos de la preparación
        stats.clear();
        mvc.perform(get(request.path()).header("Authorization", "Bearer " + request.token()))
                .andExpect(status().isOk());
        Thread.sleep(300); // incluye siempre los listeners de la propia petición (constantes)
        return stats.getPrepareStatementCount();
    }

    private String register() throws Exception {
        return registerAs("owner." + UUID.randomUUID() + "@yuyay.app");
    }

    private String registerAs(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"Prueba\"}";
        return json.readTree(postJson(null, "/api/v1/auth/register", body)).get("accessToken").asText();
    }

    private long createSubject(String token, String name) throws Exception {
        return json.readTree(postJson(token, "/api/v1/care-subjects", "{\"name\":\"" + name + "\"}")).get("id").asLong();
    }

    private String postJson(String token, String path, String body) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        return mvc.perform(req).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }
}
