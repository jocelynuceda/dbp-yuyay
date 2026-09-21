package com.yuyay.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Escenarios de autorizacion sobre CareSubject (S1-S7).
 *
 * <p>Verifica el invariante central del producto: la informacion de salud de una persona
 * solo es visible para las cuentas con una CareRelationship ACTIVE sobre ella. El filtrado
 * se comprueba a nivel de endpoint, no de DTO.
 * Usan care-subjects, relationships y health-entries
 *
 * <ul>
 *   <li>S1 - un tercero sin relación lee el sujeto                  -> 403</li>
 *   <li>S2 - un tercero lista sus sujetos                           -> 200 con lista vacia</li>
 *   <li>S3 - un tercero lee un health-entry ajeno                   -> 403</li>
 *   <li>S4 - un cuidador con relación revocada pierde el acceso     -> 403</li>
 *   <li>S5 - un CAREGIVER intenta borrar el sujeto                  -> 403</li>
 *   <li>S6 - petición sin token                                     -> 401</li>
 *   <li>S7 - petición con token expirado                            -> 401</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthorizationScenariosTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwtService;

    /** Cuenta PRINCIPAL: crea el sujeto y por tanto tiene acceso total. */
    private String ownerToken;
    /** Cuenta CAREGIVER: invitada por el owner y con la invitacion aceptada. */
    private String caregiverToken;
    /** Cuenta sin ninguna relacion con el sujeto. Es el "tercero" de S1, S2 y S3. */
    private String strangerToken;

    private long subjectId;
    private long entryId;
    private long caregiverRelationshipId;

    /**
     * Construye el escenario completo a traves de la API publica, no insertando en la base.
     * Asi el test tambien cubre que el flujo de invitacion y aceptacion funcione de verdad.
     *
     * <p>Los correos llevan un UUID porque el contexto de Spring se reutiliza entre tests y
     * la columna email es UNIQUE: sin el UUID el segundo test fallaria con 409.
     */
    @BeforeEach
    void setUp() throws Exception {
        // 1. El owner se registra y crea el sujeto. Al crearlo queda como PRINCIPAL.
        ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app");
        subjectId = postJson("/api/v1/care-subjects", ownerToken,
                "{\"name\":\"Rosa Quispe\",\"birthDate\":\"1948-03-12\"}", 201).get("id").asLong();

        // 2. Un dato de salud del sujeto, para probar el acceso fino en S3.
        entryId = postJson("/api/v1/care-subjects/" + subjectId + "/health-entries", ownerToken,
                "{\"categoryCode\":\"MEDICATION\",\"title\":\"Metformina\",\"dose\":\"850 mg\","
                        + "\"confidenceLevel\":\"CONFIRMED\"}", 201).get("id").asLong();

        // 3. El cuidador debe existir ANTES de invitarlo: invite() lo busca por correo.
        String caregiverEmail = "caregiver." + UUID.randomUUID() + "@yuyay.app";
        caregiverToken = register(caregiverEmail);

        // 4. El owner lo invita como CAREGIVER. La relacion nace en estado PENDING.
        caregiverRelationshipId = postJson("/api/v1/care-subjects/" + subjectId + "/relationships", ownerToken,
                "{\"email\":\"" + caregiverEmail + "\",\"role\":\"CAREGIVER\",\"relationshipLabel\":\"Hijo\"}", 201)
                .get("id").asLong();

        // 5. El cuidador acepta. Recien aqui la relacion pasa a ACTIVE y otorga acceso.
        mvc.perform(post("/api/v1/me/relationships/" + caregiverRelationshipId + "/accept")
                        .header("Authorization", "Bearer " + caregiverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 6. El tercero: cuenta valida, pero sin ninguna relacion con este sujeto.
        strangerToken = register("stranger." + UUID.randomUUID() + "@yuyay.app");
    }

    /**
     * S1 - Un usuario autenticado sin relacion con el sujeto no puede leerlo.
     *
     * <p>El 403 (y no 404) es deliberado: el recurso existe, lo que falta es permiso.
     * Se contrasta con el owner para probar que el 403 viene del control de acceso y no
     * de un endpoint roto.
     */
    @Test
    void strangerCannotReadCareSubject() throws Exception {
        mvc.perform(get("/api/v1/care-subjects/" + subjectId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mvc.perform(get("/api/v1/care-subjects/" + subjectId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Rosa Quispe"));
    }

    /**
     * S2 - El listado de un tercero sale vacio, no con los sujetos de otros.
     *
     * <p>Este es el escenario que mas facil se rompe: un findAll() en el repositorio
     * devolveria todos los CareSubject de la base y el DTO no lo notaria. Por eso se
     * comprueba el tamanio exacto de la lista y no solo el codigo 200.
     */
    @Test
    void strangerListReturnsEmptyArray() throws Exception {
        mvc.perform(get("/api/v1/care-subjects")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // El owner si ve el suyo: confirma que el endpoint funciona y filtra por relacion.
        mvc.perform(get("/api/v1/care-subjects")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + subjectId + ")]").exists());
    }

    /**
     * S3 - Un tercero no puede leer un dato de salud ajeno aunque conozca su id.
     *
     * <p>La autorizacion se evalua sobre el subjectId de la ruta antes de tocar la entrada,
     * asi que adivinar el entryId no sirve de nada.
     */
    @Test
    void strangerCannotReadHealthEntry() throws Exception {
        mvc.perform(get("/api/v1/care-subjects/" + subjectId + "/health-entries/" + entryId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/care-subjects/" + subjectId + "/health-entries")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }

    /**
     * S4 - Revocar la relacion corta el acceso de inmediato.
     *
     * <p>Se comprueba el antes y el despues con el mismo token: el JWT del cuidador sigue
     * siendo valido y sin expirar, asi que el 403 solo puede venir de que la relacion dejo
     * de estar ACTIVE. Es la prueba de que el permiso vive en la base y no en el token.
     */
    @Test
    void revokedCaregiverLosesAccess() throws Exception {
        // Antes de revocar: el cuidador ve al sujeto.
        mvc.perform(get("/api/v1/care-subjects/" + subjectId)
                        .header("Authorization", "Bearer " + caregiverToken))
                .andExpect(status().isOk());

        // El owner revoca la relacion (solo un PRINCIPAL puede hacerlo).
        mvc.perform(delete("/api/v1/care-subjects/" + subjectId + "/relationships/" + caregiverRelationshipId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        // Despues: mismo token, mismo endpoint, ahora 403.
        mvc.perform(get("/api/v1/care-subjects/" + subjectId)
                        .header("Authorization", "Bearer " + caregiverToken))
                .andExpect(status().isForbidden());

        // Y tampoco ve sus datos de salud.
        mvc.perform(get("/api/v1/care-subjects/" + subjectId + "/health-entries")
                        .header("Authorization", "Bearer " + caregiverToken))
                .andExpect(status().isForbidden());
    }

    /**
     * S5 - Un CAREGIVER activo tiene acceso a los datos, pero no puede borrar al sujeto.
     *
     * <p>Distingue los dos niveles de autorizacion del sistema: requireAccess (cualquier
     * relacion ACTIVE) frente a requireRole(PRINCIPAL) (solo quien administra). El mismo
     * DELETE lanzado por el owner devuelve 409 en vez de 403: eso prueba que el 403 del
     * cuidador viene del control de acceso y no de un endpoint roto.
     */
    @Test
    void caregiverCannotDeleteCareSubject() throws Exception {
        // Tiene acceso de lectura...
        mvc.perform(get("/api/v1/care-subjects/" + subjectId)
                        .header("Authorization", "Bearer " + caregiverToken))
                .andExpect(status().isOk());

        // ...pero no puede borrar ni invitar a otros cuidadores.
        mvc.perform(delete("/api/v1/care-subjects/" + subjectId)
                        .header("Authorization", "Bearer " + caregiverToken))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/care-subjects/" + subjectId + "/relationships")
                        .header("Authorization", "Bearer " + caregiverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"otro@yuyay.app\",\"role\":\"CAREGIVER\"}"))
                .andExpect(status().isForbidden());

        // El PRINCIPAL si pasa el control de acceso. No llega a 204 porque el sujeto tiene
        // historial de salud y una regla de negocio impide borrarlo, pero el codigo cambia
        // de 403 a 409: distinto codigo, distinta capa. Esa diferencia es justo lo que se
        // quiere demostrar.
        mvc.perform(delete("/api/v1/care-subjects/" + subjectId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("No se puede eliminar una persona con historial de salud"));
    }

    /**
     * S6 - Sin cabecera Authorization la respuesta es 401, con cuerpo JSON.
     *
     * <p>401 y no 403: el problema es que no hay identidad, no que la identidad no alcance.
     * Se verifica el cuerpo porque un 401 en HTML seria una fuga de la pagina de login por
     * defecto de Spring Security, y la API debe responder siempre JSON.
     */
    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/care-subjects/" + subjectId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").exists());

        mvc.perform(get("/api/v1/care-subjects"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/care-subjects/" + subjectId + "/health-entries/" + entryId))
                .andExpect(status().isUnauthorized());
    }

    /**
     * S7 - Un token firmado correctamente pero vencido tambien da 401.
     *
     * <p>El token se genera con generateDelegateToken porque es el unico metodo publico de
     * JwtService que deja fijar la expiracion; pasandole un instante pasado sale un JWT con
     * firma valida y exp vencido. Lo que se prueba es la validacion de expiracion en el
     * filtro, que es independiente del tipo de token.
     */
    @Test
    void expiredTokenReturns401() throws Exception {
        String expiredToken = jwtService.generateDelegateToken(
                1L, subjectId, "Token vencido", Instant.now().minusSeconds(60));

        mvc.perform(get("/api/v1/care-subjects/" + subjectId)
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // Un token con firma invalida cae por el mismo camino.
        mvc.perform(get("/api/v1/care-subjects/" + subjectId)
                        .header("Authorization", "Bearer no.es.un.jwt"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    /** Registra una cuenta nueva y devuelve su access token. */
    private String register(String email) throws Exception {
        return postJson("/api/v1/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"Test\"}", 201)
                .get("accessToken").asText();
    }

    /**
     * POST con cuerpo JSON que verifica el status esperado y devuelve la respuesta parseada.
     * Si token es null la peticion sale sin cabecera Authorization.
     */
    private JsonNode postJson(String path, String token, String body, int expected) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        String response = mvc.perform(req).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
