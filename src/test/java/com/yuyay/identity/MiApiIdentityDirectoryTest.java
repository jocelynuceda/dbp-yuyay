package com.yuyay.identity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MiApiIdentityDirectoryTest {

    private static final String BASE_URL = "https://miapi.cloud";
    private static final String DNI = "12345678";
    private static final String URL = BASE_URL + "/v1/dni/" + DNI;

    private MockRestServiceServer server;
    private MiApiIdentityDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        directory = new MiApiIdentityDirectory(builder, BASE_URL, "token-de-prueba");
    }

    @Test
    void readsNamesAndBirthDateAndSendsTheTokenAsABearerHeader() {
        server.expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer token-de-prueba"))
                .andRespond(withSuccess("""
                        {"success":true,"datos":{"dni":"12345678","nombres":"ANA MARIA",
                         "ape_paterno":"QUISPE","ape_materno":"ROJAS","fecha_nacimiento":"09/03/2001",
                         "domiciliado":{"direccion":"AV. DE EJEMPLO 123"}}}
                        """, MediaType.APPLICATION_JSON));

        IdentityLookup lookup = directory.findByDni(DNI);

        assertThat(lookup).isInstanceOf(IdentityLookup.Found.class);
        IdentityRecord person = ((IdentityLookup.Found) lookup).person();
        assertThat(person.fullName()).isEqualTo("ANA MARIA QUISPE ROJAS");
        assertThat(person.birthDate()).isEqualTo(LocalDate.of(2001, 3, 9));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2001-03-09", "31/02/2001"})
    void anUnreadableBirthDateIsLeftEmpty(String birthDate) {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"success":true,"datos":{"dni":"12345678","nombres":"ANA","ape_paterno":"QUISPE",
                 "fecha_nacimiento":"%s"}}
                """.formatted(birthDate), MediaType.APPLICATION_JSON));

        IdentityLookup lookup = directory.findByDni(DNI);

        assertThat(((IdentityLookup.Found) lookup).person().birthDate()).isNull();
    }

    @Test
    void anUnknownDniIsNotFound() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(directory.findByDni(DNI)).isInstanceOf(IdentityLookup.NotFound.class);
    }

    @Test
    void anEmptySuccessDoesNotProveThatTheDocumentIsMissing() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(directory.findByDni(DNI)).isInstanceOf(IdentityLookup.Unavailable.class);
    }

    @Test
    void aResponseForAnotherDocumentIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"success":true,"datos":{"dni":"00000000","nombres":"ANA","ape_paterno":"QUISPE"}}
                """, MediaType.APPLICATION_JSON));
        assertThat(directory.findByDni(DNI)).isInstanceOf(IdentityLookup.Unavailable.class);
    }

    @Test
    void aRejectedCredentialIsUnavailableAndNotNotFound() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(directory.findByDni(DNI)).isInstanceOf(IdentityLookup.Unavailable.class);
    }

    @Test
    void aServerErrorIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(directory.findByDni(DNI))
                .isInstanceOf(IdentityLookup.Unavailable.class);
    }

    @Test
    void aConnectionFailureIsUnavailableAndNeverThrows() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new IOException("conexión rechazada");
        });

        assertThat(directory.findByDni(DNI))
                .isInstanceOf(IdentityLookup.Unavailable.class);
    }

    @Test
    void aMalformedDniSpendsNoQuery() {
        assertThatThrownBy(() -> directory.findByDni("1234"))
                .isInstanceOf(IllegalArgumentException.class);

        server.verify(); // ninguna petición esperada ni realizada
    }

    @Test
    void theDniNeverReachesTheLogWhenTheCredentialIsRejected() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(logsOf(() -> directory.findByDni(DNI)))
                .isNotEmpty()
                .noneMatch(message -> message.contains(DNI));
    }

    /**
     * El caso que de verdad lo arriesga: el mensaje de la excepción de red lleva dentro la URI de la
     * petición, y la URI lleva el DNI. Registrarlo guardaría el dato personal en un log que se
     * conserva.
     */
    @Test
    void theDniNeverReachesTheLogWhenTheConnectionFails() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new IOException("conexión rechazada");
        });

        assertThat(logsOf(() -> directory.findByDni(DNI)))
                .isNotEmpty()
                .noneMatch(message -> message.contains(DNI));
    }

    @Test
    void theDniNeverReachesTheLogWhenTheBodyCannotBeRead() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"nombres\": no es json}", MediaType.APPLICATION_JSON));

        assertThat(logsOf(() -> assertThat(directory.findByDni(DNI))
                .isInstanceOf(IdentityLookup.Unavailable.class)))
                .noneMatch(message -> message.contains(DNI));
    }

    private List<String> logsOf(Runnable call) {
        Logger logger = (Logger) LoggerFactory.getLogger(MiApiIdentityDirectory.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            call.run();
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
