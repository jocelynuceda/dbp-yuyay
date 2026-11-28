package com.yuyay.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Directorio de identidad sobre la consulta de DNI de miapi.cloud ({@code GET /v1/dni/{dni}}).
 *
 * <p>La respuesta trae además la dirección y la edad. No se leen: solo los nombres, para
 * contrastarlos, y la fecha de nacimiento, para la edad mínima del registro. Nada se guarda.
 */
@Slf4j
@Component
@ConditionalOnExpression("!'${app.reniec.api-token:}'.isEmpty()")
public class MiApiIdentityDirectory implements IdentityDirectory {

    private static final DateTimeFormatter BIRTH_DATE =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private final RestClient client;

    @Autowired
    public MiApiIdentityDirectory(
            @Value("${app.reniec.base-url:https://miapi.cloud}") String baseUrl,
            @Value("${app.reniec.api-token:}") String apiToken,
            @Value("${app.reniec.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${app.reniec.read-timeout-seconds:5}") long readTimeoutSeconds) {
        this(RestClient.builder().requestFactory(
                        requestFactory(connectTimeoutSeconds, readTimeoutSeconds)),
                baseUrl, apiToken);
    }

    // Visible para las pruebas: permite inyectar un RestClient.Builder con servidor simulado.
    MiApiIdentityDirectory(RestClient.Builder builder, String baseUrl, String apiToken) {
        this.client = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(long connectSeconds, long readSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectSeconds))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readSeconds));
        return factory;
    }

    @Override
    public IdentityLookup findByDni(String dni) {
        DniFormat.require(dni);
        try {
            return client.get()
                    .uri("/v1/dni/{dni}", dni)
                    .exchange((request, response) -> read(dni, response.getStatusCode(), body(response)));
        } catch (RestClientException | IllegalStateException e) {
            // Solo el tipo de fallo. El mensaje de la excepción incluye la URI de la petición, y la
            // URI lleva el DNI dentro: registrarlo guardaría el dato personal que esta clase promete
            // no registrar, y los logs se conservan.
            log.warn("No se pudo consultar el DNI en miapi.cloud: {}", e.getClass().getSimpleName());
            return new IdentityLookup.Unavailable("El servicio de consulta no respondió");
        }
    }

    private DniResponse body(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(DniResponse.class);
        } catch (RestClientException e) {
            // Tampoco el mensaje: al fallar la lectura puede arrastrar el cuerpo, con nombres dentro.
            log.warn("Respuesta de miapi.cloud que no se pudo leer: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private IdentityLookup read(String dni, HttpStatusCode status, DniResponse body) {
        if (status.value() == 404 || status.value() == 422) {
            return new IdentityLookup.NotFound();
        }
        if (status.value() == 401 || status.value() == 403) {
            log.error("miapi.cloud rechazó la credencial (HTTP {}): revisa RENIEC_API_TOKEN", status.value());
            return new IdentityLookup.Unavailable("La credencial del servicio de consulta no es válida");
        }
        if (!status.is2xxSuccessful()) {
            log.warn("miapi.cloud respondió HTTP {}", status.value());
            return new IdentityLookup.Unavailable("El servicio de consulta respondió con un error");
        }
        Datos datos = body == null || !Boolean.TRUE.equals(body.success()) ? null : body.datos();
        if (datos == null || !dni.equals(datos.dni()) || isBlank(datos.nombres())
                || (isBlank(datos.apellidoPaterno()) && isBlank(datos.apellidoMaterno()))) {
            return new IdentityLookup.Unavailable("Respuesta incompleta del servicio de consulta");
        }
        return new IdentityLookup.Found(new IdentityRecord(
                datos.nombres().strip(),
                datos.apellidoPaterno() == null ? "" : datos.apellidoPaterno().strip(),
                datos.apellidoMaterno() == null ? "" : datos.apellidoMaterno().strip(),
                birthDate(datos.fechaNacimiento())));
    }

    /** {@code null} si falta o no es una fecha real: quien exija la edad debe tratarlo como no comprobable. */
    private static LocalDate birthDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip(), BIRTH_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DniResponse(Boolean success, Datos datos) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Datos(
            String dni,
            String nombres,
            @JsonProperty("ape_paterno") String apellidoPaterno,
            @JsonProperty("ape_materno") String apellidoMaterno,
            @JsonProperty("fecha_nacimiento") String fechaNacimiento
    ) {
    }
}
