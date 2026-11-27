package com.yuyay.attachment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yuyay.attachment.ocr.TextExtractor;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.attachment.storage.FileStorage;
import com.yuyay.attachment.web.AttachmentRequestLogFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every upload and OCR retry leaves one line with its status and client, including the ones rejected before a file
 * is stored. A request with no line never reached the backend.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttachmentRequestLogIntegrationTest {
    private static final byte[] FAKE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final String ANDROID = "android/1.0.0+0f3a9c12";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AttachmentRepository attachmentRepository;

    @MockitoBean FileStorage fileStorage;
    @MockitoBean TextExtractor textExtractor;

    private final Logger logger = (Logger) LoggerFactory.getLogger(AttachmentRequestLogFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private String token;
    private long subjectId;

    @BeforeEach
    void setUp() throws Exception {
        token = register("requests." + UUID.randomUUID() + "@yuyay.app");
        subjectId = json.readTree(mvc.perform(post("/api/v1/care-subjects").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Rosa Quispe\",\"birthDate\":\"1948-03-12\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void anAcceptedUploadIsLoggedAndTheClientIsStored() throws Exception {
        String body = mvc.perform(multipart(url()).file(jpeg()).header("Authorization", "Bearer " + token)
                        .header("X-Yuyay-Client", ANDROID))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String line = onlyLine();
        assertTrue(line.startsWith("Solicitud de adjunto endpoint=upload status=201 durationMs="), line);
        assertTrue(line.endsWith(" client=" + ANDROID), line);
        // MockMvc builds multipart requests without a body length; a real server sends the byte count here.
        assertTrue(line.matches(".* contentLength=(\\d+|unavailable) .*"), line);
        long id = json.readTree(body).get("id").asLong();
        assertEquals(ANDROID, attachmentRepository.findById(id).orElseThrow().getUploadClient());
    }

    @Test
    void rejectedUploadsAreLoggedWithTheirStatus() throws Exception {
        MockMultipartFile text = new MockMultipartFile("file", "nota.txt", "text/plain", FAKE_JPEG);
        mvc.perform(multipart(url()).file(text).header("Authorization", "Bearer " + token).header("X-Yuyay-Client", "web"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(url()).file(jpeg()).header("X-Yuyay-Client", ANDROID))
                .andExpect(status().isUnauthorized());

        List<String> lines = lines();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("Solicitud de adjunto endpoint=upload status=400 "), lines.get(0));
        assertTrue(lines.get(0).endsWith(" client=web"), lines.get(0));
        assertTrue(lines.get(1).startsWith("Solicitud de adjunto endpoint=upload status=401 "), lines.get(1));
        assertTrue(lines.get(1).endsWith(" client=" + ANDROID), lines.get(1));
    }

    @Test
    void ocrRetriesAreLoggedAsTheirOwnEndpoint() throws Exception {
        long id = json.readTree(mvc.perform(multipart(url()).file(jpeg()).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        appender.list.clear();

        // The OCR did not fail, so the retry is refused with 409; it must still leave its line.
        mvc.perform(post(url() + "/" + id + "/ocr-retry").header("Authorization", "Bearer " + token)
                        .header("X-Yuyay-Client", "web"))
                .andExpect(status().isConflict());

        String line = onlyLine();
        assertTrue(line.startsWith("Solicitud de adjunto endpoint=ocr-retry status=409 "), line);
        assertTrue(line.endsWith(" client=web"), line);
    }

    /**
     * The header is informational: a missing value, personal text, a forged log line or a C1 control character
     * (bytes 0x80-0x9F, which a server decodes from a header as ISO-8859-1, as with UTF-8 "Á") never blocks the
     * upload. Spring Security's firewall rejects control characters when a header is read, so only the filter,
     * which runs before it, may read this one.
     */
    @Test
    void aMissingOrMalformedClientNeverBlocksTheUpload() throws Exception {
        for (String value : new String[]{null, "web; Ana Pérez\nINFO forged", "\u00C3\u0081", "a".repeat(65)}) {
            appender.list.clear();
            var request = multipart(url()).file(jpeg()).header("Authorization", "Bearer " + token);
            if (value != null) request = request.header("X-Yuyay-Client", value);
            String body = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

            String line = onlyLine();
            assertTrue(line.startsWith("Solicitud de adjunto endpoint=upload status=201 "), line);
            assertTrue(line.endsWith(" client=unavailable"), line);
            assertTrue(!line.contains("Ana") && !line.contains("forged"), line);
            assertNull(attachmentRepository.findById(json.readTree(body).get("id").asLong()).orElseThrow().getUploadClient(),
                    String.valueOf(value));
        }
    }

    @Test
    void anUploadToSomeoneElsesPersonIsLoggedAs403() throws Exception {
        String stranger = register("stranger." + UUID.randomUUID() + "@yuyay.app");
        appender.list.clear();

        mvc.perform(multipart(url()).file(jpeg()).header("Authorization", "Bearer " + stranger).header("X-Yuyay-Client", "web"))
                .andExpect(status().isForbidden());

        assertTrue(onlyLine().startsWith("Solicitud de adjunto endpoint=upload status=403 "), onlyLine());
    }

    @Test
    void readsAndOtherEndpointsAreNotLogged() throws Exception {
        mvc.perform(get(url()).header("Authorization", "Bearer " + token).header("X-Yuyay-Client", "web"))
                .andExpect(status().isOk());

        assertTrue(lines().isEmpty(), lines().toString());
    }

    @Test
    void browsersMaySendTheClientHeaderCrossOrigin() throws Exception {
        mvc.perform(options(url())
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,x-yuyay-client"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    private String url() {
        return "/api/v1/care-subjects/" + subjectId + "/attachments";
    }

    private static MockMultipartFile jpeg() {
        return new MockMultipartFile("file", "receta.jpg", "image/jpeg", FAKE_JPEG);
    }

    /** Lines from this test thread only: other tests in the same JVM may be uploading at the same time. */
    private List<String> lines() {
        String thread = Thread.currentThread().getName();
        return appender.list.stream()
                .filter(event -> thread.equals(event.getThreadName()) && event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private String onlyLine() {
        List<String> lines = lines();
        assertEquals(1, lines.size(), lines.toString());
        return lines.getFirst();
    }

    private String register(String email) throws Exception {
        JsonNode node = json.readTree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"termsAccepted\":true,\"termsVersion\":\"1.2\",\"dni\":\"12345678\",\"password\":\"Secreta123\",\"name\":\"Test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return node.get("accessToken").asText();
    }
}
