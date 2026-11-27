package com.yuyay.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

/**
 * Directorio de identidad sobre la consulta de DNI de APIsPERU ({@code dniruc.apisperu.com}).
 *
 * <p><b>Este proveedor no devuelve la fecha de nacimiento.</b> Su respuesta documentada es
 * {@code dni, nombres, apellidoPaterno, apellidoMaterno, codVerifica}. Sirve para comprobar que un
 * DNI existe y a qué nombre figura; <b>no sirve para comprobar una edad</b>. Por eso la compuerta
 * de edad del canje se resuelve con la fecha declarada en el perfil y esta consulta solo contrasta
 * el nombre: ver {@code docs/modelo-autocuidado-y-recordatorios.md} §3.5.
 */
@Slf4j
@Component
@ConditionalOnExpression("!'${app.reniec.api-token:}'.isEmpty()")
public class ApisPeruIdentityDirectory implements IdentityDirectory {

    private final RestClient client;

    @Autowired
    public ApisPeruIdentityDirectory(
            @Value("${app.reniec.base-url:https://dniruc.apisperu.com}") String baseUrl,
            @Value("${app.reniec.api-token:}") String apiToken,
            @Value("${app.reniec.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${app.reniec.read-timeout-seconds:5}") long readTimeoutSeconds) {
        this(RestClient.builder().requestFactory(
                        requestFactory(connectTimeoutSeconds, readTimeoutSeconds)),
                baseUrl, apiToken);
    }

    // Visible para las pruebas: permite inyectar un RestClient.Builder con servidor simulado.
    ApisPeruIdentityDirectory(RestClient.Builder builder, String baseUrl, String apiToken) {
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
                    .uri("/api/v1/dni/{dni}", dni)
                    .exchange((request, response) -> read(response.getStatusCode(), body(response)));
        } catch (RestClientException | IllegalStateException e) {
            // Solo el tipo de fallo. El mensaje de la excepción incluye la URI de la petición, y la
            // URI lleva el DNI dentro: registrarlo guardaría el dato personal que esta clase promete
            // no registrar, y los logs se conservan.
            log.warn("No se pudo consultar el DNI en APIsPERU: {}", e.getClass().getSimpleName());
            return new IdentityLookup.Unavailable("El servicio de consulta no respondió");
        }
    }

    private DniResponse body(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(DniResponse.class);
        } catch (RestClientException e) {
            // Tampoco el mensaje: al fallar la lectura puede arrastrar el cuerpo, con nombres dentro.
            log.warn("Respuesta de APIsPERU que no se pudo leer: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private IdentityLookup read(HttpStatusCode status, DniResponse body) {
        if (status.value() == 404 || status.value() == 422) {
            return new IdentityLookup.NotFound();
        }
        if (status.value() == 401 || status.value() == 403) {
            log.error("APIsPERU rechazó la credencial (HTTP {}): revisa RENIEC_API_TOKEN", status.value());
            return new IdentityLookup.Unavailable("La credencial del servicio de consulta no es válida");
        }
        if (!status.is2xxSuccessful()) {
            log.warn("APIsPERU respondió HTTP {}", status.value());
            return new IdentityLookup.Unavailable("El servicio de consulta respondió con un error");
        }
        if (body == null || body.nombres() == null || body.nombres().isBlank()) {
            // Algunos errores del proveedor llegan como 200 con un cuerpo sin datos.
            return new IdentityLookup.NotFound();
        }
        return new IdentityLookup.Found(new IdentityRecord(
                body.nombres().strip(),
                body.apellidoPaterno() == null ? "" : body.apellidoPaterno().strip(),
                body.apellidoMaterno() == null ? "" : body.apellidoMaterno().strip()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DniResponse(
            String nombres,
            String apellidoPaterno,
            String apellidoMaterno
    ) {
    }
}
