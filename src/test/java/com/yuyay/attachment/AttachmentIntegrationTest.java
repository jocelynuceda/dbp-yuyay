package com.yuyay.attachment;

import com.yuyay.attachment.dto.AttachmentAccessResponse;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.exception.FileStorageException;
import com.yuyay.attachment.ocr.TextExtractor;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.attachment.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo del OCR de punta a punta sobre HTTP: subida, extraccion asincrona y confirmacion por el cuidador.
 * Solo se simulan los bordes con AWS (S3 y Textract): el CI no tiene credenciales y el test no debe
 * depender de la red. Todo lo demas (seguridad, JPA, eventos AFTER_COMMIT, @Async) corre de verdad.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttachmentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AttachmentRepository attachmentRepository;

    @MockitoBean FileStorage fileStorage;
    @MockitoBean TextExtractor textExtractor;

    /** Firmas reales ("magic numbers"): el servicio compara los primeros bytes con el Content-Type. */
    private static final byte[] FAKE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final byte[] FAKE_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
    private static final byte[] FAKE_PDF = "%PDF-1.7\n%fake".getBytes();
    private static final byte[] FAKE_M4A = {0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x4D, 0x34, 0x41, 0x20};

    private String ownerToken;
    private String strangerToken;
    private long subjectId;
    private long otherSubjectId;

    @BeforeEach
    void setUp() throws Exception {
        ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app");
        subjectId = postJson("/api/v1/care-subjects", ownerToken,
                "{\"name\":\"Rosa Quispe\",\"birthDate\":\"1948-03-12\"}", 201).get("id").asLong();
        otherSubjectId = postJson("/api/v1/care-subjects", ownerToken,
                "{\"name\":\"Diego Ramos\",\"birthDate\":\"2020-05-01\"}", 201).get("id").asLong();
        strangerToken = register("stranger." + UUID.randomUUID() + "@yuyay.app");
    }

    // ---------- Flujo principal ----------

    @Test
    void uploadReturns201AndOcrCompletesInBackground() throws Exception {
        when(textExtractor.extractText(anyString())).thenReturn("Metformina 850 mg\n1 tableta cada 12 horas");

        JsonNode created = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201);
        assertEquals("PENDING", created.get("ocrStatus").asText());
        assertEquals("receta.jpg", created.get("originalFilename").asText());

        JsonNode done = awaitOcr(subjectId, created.get("id").asLong());
        assertEquals("DONE", done.get("ocrStatus").asText());
        assertTrue(done.get("ocrText").asText().contains("Metformina 850 mg"));
        verify(fileStorage).store(startsWith("care-subjects/" + subjectId + "/"), any(byte[].class), eq("image/jpeg"));
        verify(fileStorage, never()).delete(anyString());
    }

    @Test
    void endToEndFromPhotoToConfirmedEntry() throws Exception {
        when(textExtractor.extractText(anyString())).thenReturn("Metformina 850 mg");
        long attachmentId = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();

        // 1. La cuidadora espera el OCR y lee el texto sugerido.
        JsonNode done = awaitOcr(subjectId, attachmentId);
        assertEquals("Metformina 850 mg", done.get("ocrText").asText());

        // 2. Confirma el dato a mano: la version queda enlazada a su evidencia.
        JsonNode entry = postJson("/api/v1/care-subjects/" + subjectId + "/health-entries", ownerToken,
                entryBody(attachmentId), 201);
        assertEquals(attachmentId, entry.get("latestVersion").get("sourceAttachmentId").asLong());

        // 3. Un adjunto de otra persona no puede respaldar el dato.
        postJson("/api/v1/care-subjects/" + otherSubjectId + "/health-entries", ownerToken,
                entryBody(attachmentId), 400);
    }

    @Test
    void ocrNeverCreatesHealthEntriesByItself() throws Exception {
        when(textExtractor.extractText(anyString())).thenReturn("Losartan 50 mg\n1 tableta al dia");
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        assertEquals("DONE", awaitOcr(subjectId, id).get("ocrStatus").asText());

        mvc.perform(get("/api/v1/care-subjects/" + subjectId + "/health-entries")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void acceptsPngAndPdfToo() throws Exception {
        upload(subjectId, ownerToken, "receta.png", "image/png", FAKE_PNG, 201);
        upload(subjectId, ownerToken, "receta.pdf", "application/pdf", FAKE_PDF, 201);
        verify(fileStorage).store(matches("care-subjects/" + subjectId + "/[0-9a-f-]{36}\\.pdf"),
                any(byte[].class), eq("application/pdf"));
    }

    @Test
    void listReturnsNewestFirstAndOnlyThatSubject() throws Exception {
        upload(subjectId, ownerToken, "primera.jpg", "image/jpeg", FAKE_JPEG, 201);
        Thread.sleep(20);
        upload(subjectId, ownerToken, "segunda.png", "image/png", FAKE_PNG, 201);
        upload(otherSubjectId, ownerToken, "de-diego.jpg", "image/jpeg", FAKE_JPEG, 201);

        mvc.perform(get(url(subjectId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].originalFilename").value("segunda.png"))
                .andExpect(jsonPath("$[1].originalFilename").value("primera.jpg"));
    }

    // ---------- Fallos del OCR ----------

    @Test
    void ocrFailureIsStoredAndCanBeRetried() throws Exception {
        when(textExtractor.extractText(anyString()))
                .thenThrow(new RuntimeException("Textract no disponible"))
                .thenReturn("Losartan 50 mg");

        long id = upload(subjectId, ownerToken, "receta.png", "image/png", FAKE_PNG, 201).get("id").asLong();
        JsonNode failed = awaitOcr(subjectId, id);
        assertEquals("FAILED", failed.get("ocrStatus").asText());
        assertEquals("No se pudo extraer el texto. Puedes reintentar el OCR.", failed.get("ocrError").asText());

        mvc.perform(post(url(subjectId) + "/" + id + "/ocr-retry").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ocrStatus").value("PENDING"));

        JsonNode retried = awaitOcr(subjectId, id);
        assertEquals("DONE", retried.get("ocrStatus").asText());
        assertEquals("Losartan 50 mg", retried.get("ocrText").asText());
    }

    @Test
    void retryIsRejectedWhenOcrDidNotFail() throws Exception {
        when(textExtractor.extractText(anyString())).thenReturn("texto");
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        awaitOcr(subjectId, id);

        mvc.perform(post(url(subjectId) + "/" + id + "/ocr-retry").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());
    }

    @Test
    void sensitiveOcrErrorIsReplacedWithSafeMessage() throws Exception {
        when(textExtractor.extractText(anyString())).thenThrow(new RuntimeException("AWS request, bucket and credentials: " + "x".repeat(2000)));
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();

        JsonNode failed = awaitOcr(subjectId, id);
        assertEquals("FAILED", failed.get("ocrStatus").asText());
        assertEquals("No se pudo extraer el texto. Puedes reintentar el OCR.", failed.get("ocrError").asText());
    }

    // ---------- Fallos de S3 ----------

    @Test
    void historicalRawOcrErrorIsSanitizedOnDetailAndList() throws Exception {
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        awaitOcr(subjectId, id);
        var attachment = attachmentRepository.findById(id).orElseThrow();
        attachment.setOcrStatus(OcrStatus.FAILED);
        attachment.setOcrError("AWS historical bucket, endpoint and request details");
        attachmentRepository.saveAndFlush(attachment);
        mvc.perform(get(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ocrError").value("No se pudo extraer el texto. Puedes reintentar el OCR."));
        mvc.perform(get(url(subjectId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ocrError").value("No se pudo extraer el texto. Puedes reintentar el OCR."));
    }

    @Test
    void storageFailureReturns502AndLeavesNoRecord() throws Exception {
        doThrow(new FileStorageException("S3 no disponible"))
                .when(fileStorage).store(anyString(), any(byte[].class), anyString());

        upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 502);

        mvc.perform(get(url(subjectId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- Validacion del archivo ----------

    @Test
    void invalidFilesReturn400() throws Exception {
        upload(subjectId, ownerToken, "nota.txt", "text/plain", FAKE_JPEG, 400);
        upload(subjectId, ownerToken, "vacia.jpg", "image/jpeg", new byte[0], 400);
        upload(subjectId, ownerToken, "enorme.jpg", "image/jpeg", new byte[10 * 1024 * 1024 + 1], 400);
        upload(subjectId, ownerToken, "sin-tipo.jpg", null, FAKE_JPEG, 400);

        mvc.perform(multipart(url(subjectId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(fileStorage);
    }

    @Test
    void spoofedContentTypeIsRejected() throws Exception {
        byte[] windowsExecutable = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03};
        upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", windowsExecutable, 400);
        upload(subjectId, ownerToken, "receta.pdf", "application/pdf", FAKE_PNG, 400);

        verifyNoInteractions(fileStorage);
    }

    @Test
    void filenameIsSanitizedAndNeverUsedAsStorageKey() throws Exception {
        JsonNode created = upload(subjectId, ownerToken, "../../etc/receta.jpg", "image/jpeg", FAKE_JPEG, 201);

        assertEquals("receta.jpg", created.get("originalFilename").asText());
        verify(fileStorage).store(matches("care-subjects/" + subjectId + "/[0-9a-f-]{36}\\.jpg"),
                any(byte[].class), eq("image/jpeg"));
    }

    @Test
    void consultationMustBelongToTheSameSubjectAndIsCheckedBeforeUploading() throws Exception {
        long foreignConsultation = createConsultation(otherSubjectId);
        long ownConsultation = createConsultation(subjectId);

        uploadWithConsultation(foreignConsultation, 400);
        verifyNoInteractions(fileStorage);

        JsonNode created = uploadWithConsultation(ownConsultation, 201);
        assertEquals(ownConsultation, created.get("consultationId").asLong());
    }

    // ---------- Seguridad ----------

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "receta.jpg", "image/jpeg", FAKE_JPEG);
        mvc.perform(multipart(url(subjectId)).file(file)).andExpect(status().isUnauthorized());
        mvc.perform(get(url(subjectId))).andExpect(status().isUnauthorized());
        verifyNoInteractions(fileStorage);
    }

    @Test
    void strangerCannotUploadListOrRead() throws Exception {
        upload(subjectId, strangerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 403);
        verifyNoInteractions(fileStorage);

        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();

        mvc.perform(get(url(subjectId) + "/" + id).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get(url(subjectId)).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void invitedCaregiverCanUploadOnlyAfterAccepting() throws Exception {
        String brunoEmail = "bruno." + UUID.randomUUID() + "@yuyay.app";
        String brunoToken = register(brunoEmail);
        long relationshipId = postJson("/api/v1/care-subjects/" + subjectId + "/relationships", ownerToken,
                "{\"email\":\"" + brunoEmail + "\",\"role\":\"CAREGIVER\",\"relationshipLabel\":\"hijo\"}", 201)
                .get("id").asLong();

        upload(subjectId, brunoToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 403);
        mvc.perform(get(url(subjectId) + "/1/access").header("Authorization", "Bearer " + brunoToken))
                .andExpect(status().isForbidden());
        mvc.perform(delete(url(subjectId) + "/1").header("Authorization", "Bearer " + brunoToken))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/me/relationships/" + relationshipId + "/accept")
                        .header("Authorization", "Bearer " + brunoToken))
                .andExpect(status().isOk());

        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        awaitOcr(subjectId, id);
        when(fileStorage.createAccess(anyString(), anyString(), any(Duration.class)))
                .thenReturn(new AttachmentAccessResponse("https://example.invalid/temporary", Instant.now().plusSeconds(300)));
        mvc.perform(get(url(subjectId) + "/" + id + "/access").header("Authorization", "Bearer " + brunoToken))
                .andExpect(status().isOk());
        mvc.perform(delete(url(subjectId) + "/" + id).header("Authorization", "Bearer " + brunoToken))
                .andExpect(status().isNoContent());
        long remainingId = upload(subjectId, brunoToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        awaitOcr(subjectId, remainingId);
        mvc.perform(delete("/api/v1/care-subjects/" + subjectId + "/relationships/" + relationshipId)
                        .header("Authorization", "Bearer " + ownerToken)).andExpect(status().isNoContent());
        mvc.perform(get(url(subjectId) + "/" + remainingId + "/access").header("Authorization", "Bearer " + brunoToken))
                .andExpect(status().isForbidden());
        mvc.perform(delete(url(subjectId) + "/" + remainingId).header("Authorization", "Bearer " + brunoToken))
                .andExpect(status().isForbidden());
    }

    // ---------- Audio, descarga y borrado ----------

    @Test
    void audioIsStoredWithoutRunningOcr() throws Exception {
        JsonNode created = upload(subjectId, ownerToken, "nota.m4a", "audio/mp4", FAKE_M4A, 201);

        assertEquals("DONE", created.get("ocrStatus").asText());
        verify(fileStorage).store(matches(".*\\.m4a$"), any(byte[].class), eq("audio/mp4"));
        verifyNoInteractions(textExtractor);
    }

    @Test
    void audioWithWrongSignatureIsRejected() throws Exception {
        upload(subjectId, ownerToken, "nota.m4a", "audio/mp4", FAKE_JPEG, 400);
        verify(fileStorage, never()).store(anyString(), any(byte[].class), anyString());
    }

    @Test
    void contentIsServedOnlyToPeopleWithAccess() throws Exception {
        long id = upload(subjectId, ownerToken, "nota.m4a", "audio/mp4", FAKE_M4A, 201).get("id").asLong();
        when(fileStorage.load(anyString())).thenReturn(FAKE_M4A);
        String path = "/api/v1/care-subjects/" + subjectId + "/attachments/" + id + "/content";

        mvc.perform(get(path).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mp4"))
                .andExpect(content().bytes(FAKE_M4A));
        mvc.perform(get(path).header("Authorization", "Bearer " + ownerToken).header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-3/" + FAKE_M4A.length));
        mvc.perform(get(path).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void deleteRemovesRowAndStoredFile() throws Exception {
        long id = upload(subjectId, ownerToken, "nota.m4a", "audio/mp4", FAKE_M4A, 201).get("id").asLong();
        String path = "/api/v1/care-subjects/" + subjectId + "/attachments/" + id;

        mvc.perform(delete(path).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
        verify(fileStorage, never()).delete(anyString());

        mvc.perform(delete(path).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        verify(fileStorage).delete(startsWith("care-subjects/" + subjectId + "/"));
        mvc.perform(get(path).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void caregiverCanDeleteOnlyWhatTheyUploaded() throws Exception {
        String brunoEmail = "bruno." + UUID.randomUUID() + "@yuyay.app";
        String brunoToken = register(brunoEmail);
        long relationshipId = postJson("/api/v1/care-subjects/" + subjectId + "/relationships", ownerToken,
                "{\"email\":\"" + brunoEmail + "\",\"role\":\"CAREGIVER\",\"relationshipLabel\":\"hijo\"}", 201)
                .get("id").asLong();
        mvc.perform(post("/api/v1/me/relationships/" + relationshipId + "/accept")
                        .header("Authorization", "Bearer " + brunoToken))
                .andExpect(status().isOk());
        long ownerFile = upload(subjectId, ownerToken, "a.m4a", "audio/mp4", FAKE_M4A, 201).get("id").asLong();
        long brunoFile = upload(subjectId, brunoToken, "b.m4a", "audio/mp4", FAKE_M4A, 201).get("id").asLong();
        String base = "/api/v1/care-subjects/" + subjectId + "/attachments/";

        mvc.perform(delete(base + ownerFile).header("Authorization", "Bearer " + brunoToken))
                .andExpect(status().isForbidden());
        mvc.perform(delete(base + brunoFile).header("Authorization", "Bearer " + brunoToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void attachmentBackingAnEntryCannotBeDeleted() throws Exception {
        long id = upload(subjectId, ownerToken, "nota.m4a", "audio/mp4", FAKE_M4A, 201).get("id").asLong();
        postJson("/api/v1/care-subjects/" + subjectId + "/health-entries", ownerToken, entryBody(id), 201);

        int status = mvc.perform(delete("/api/v1/care-subjects/" + subjectId + "/attachments/" + id)
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getStatus();

        assertTrue(status >= 400 && status < 500);
        verify(fileStorage, never()).delete(anyString());
    }

    @Test
    void attachmentOfAnotherSubjectIsNotFound() throws Exception {
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();

        mvc.perform(get(url(otherSubjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void accessReturnsTemporaryUrlWithoutCachingAndKeepsListContract() throws Exception {
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        Instant expiresAt = Instant.parse("2030-01-01T00:05:00Z");
        when(fileStorage.createAccess(anyString(), eq("image/jpeg"), eq(Duration.ofMinutes(5))))
                .thenReturn(new AttachmentAccessResponse("https://example.invalid/temporary", expiresAt));
        mvc.perform(get(url(subjectId) + "/" + id + "/access").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.url").value("https://example.invalid/temporary"))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()));
        verify(fileStorage).createAccess(startsWith("care-subjects/" + subjectId + "/"), eq("image/jpeg"), eq(Duration.ofMinutes(5)));
        mvc.perform(get(url(subjectId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].url").doesNotExist());
    }

    @Test
    void accessFailureReturnsSafe502() throws Exception {
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        when(fileStorage.createAccess(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new FileStorageException("No se pudo abrir el archivo"));
        mvc.perform(get(url(subjectId) + "/" + id + "/access").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("No se pudo abrir el archivo"));
    }

    @Test
    void accessAndDeleteEnforceAuthenticationMembershipAndSubjectScope() throws Exception {
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        mvc.perform(get(url(subjectId) + "/" + id + "/access")).andExpect(status().isUnauthorized());
        mvc.perform(delete(url(subjectId) + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get(url(subjectId) + "/" + id + "/access").header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
        mvc.perform(delete(url(subjectId) + "/" + id).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get(url(otherSubjectId) + "/" + id + "/access").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mvc.perform(delete(url(otherSubjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        verify(fileStorage, never()).createAccess(anyString(), anyString(), any(Duration.class));
        verify(fileStorage, never()).deleteRequired(anyString());
    }

    @Test
    void deleteRemovesFileMetadataAndSubsequentAccess() throws Exception {
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        awaitOcr(subjectId, id);
        mvc.perform(delete(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        verify(fileStorage).deleteRequired(startsWith("care-subjects/" + subjectId + "/"));
        mvc.perform(get(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mvc.perform(get(url(subjectId) + "/" + id + "/access").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mvc.perform(delete(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mvc.perform(get(url(subjectId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void failedDeleteRollsBackMetadataAndAllowsRetry() throws Exception {
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        awaitOcr(subjectId, id);
        org.mockito.Mockito.doThrow(new FileStorageException("No se pudo eliminar el archivo"))
                .doNothing().when(fileStorage).deleteRequired(anyString());
        mvc.perform(delete(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("No se pudo eliminar el archivo"));
        mvc.perform(get(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.originalFilename").value("receta.jpg"));
        mvc.perform(delete(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void evidenceCannotBeDeletedAndSurvivesHealthEntryEdit() throws Exception {
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
        awaitOcr(subjectId, id);
        long entryId = postJson("/api/v1/care-subjects/" + subjectId + "/health-entries", ownerToken,
                entryBody(id), 201).get("id").asLong();
        mvc.perform(patch("/api/v1/care-subjects/" + subjectId + "/health-entries/" + entryId)
                        .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Metformina actualizada\",\"dose\":\"850 mg\",\"confidenceLevel\":\"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion.sourceAttachmentId").value(id));
        mvc.perform(delete(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());
        verify(fileStorage, never()).deleteRequired(anyString());
        mvc.perform(get(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    void deleteWaitsForOcrAndOcrCannotRestoreDeletedMetadata() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(textExtractor.extractText(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) throw new AssertionError("No se liberó el OCR de prueba");
            return "texto";
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_JPEG, 201).get("id").asLong();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CountDownLatch deleteStarted = new CountDownLatch(1);
            var deletion = executor.submit(() -> {
                deleteStarted.countDown();
                return mvc.perform(delete(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                        .andReturn().getResponse().getStatus();
            });
            assertTrue(deleteStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> deletion.get(250, TimeUnit.MILLISECONDS));
            verify(fileStorage, never()).deleteRequired(anyString());
            finish.countDown();
            assertEquals(204, deletion.get(5, TimeUnit.SECONDS));
            mvc.perform(get(url(subjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNotFound());
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    // ---------- Helpers ----------

    private String entryBody(long attachmentId) {
        return "{\"categoryCode\":\"MEDICATION\",\"title\":\"Metformina\",\"dose\":\"850 mg\","
                + "\"confidenceLevel\":\"CONFIRMED\",\"sourceAttachmentId\":" + attachmentId + "}";
    }

    private String url(long subject) {
        return "/api/v1/care-subjects/" + subject + "/attachments";
    }

    private long createConsultation(long subject) throws Exception {
        return postJson("/api/v1/care-subjects/" + subject + "/consultations", ownerToken,
                "{\"date\":\"" + LocalDate.now().minusDays(1) + "\",\"reason\":\"Control\"}", 201)
                .get("id").asLong();
    }

    private JsonNode uploadWithConsultation(long consultationId, int expected) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "receta.jpg", "image/jpeg", FAKE_JPEG);
        String body = mvc.perform(multipart(url(subjectId)).file(file)
                        .param("consultationId", String.valueOf(consultationId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode upload(long subject, String token, String filename, String contentType,
                            byte[] content, int expected) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, content);
        String body = mvc.perform(multipart(url(subject)).file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode awaitOcr(long subject, long attachmentId) throws Exception {
        for (int i = 0; i < 50; i++) {
            String body = mvc.perform(get(url(subject) + "/" + attachmentId).header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode node = json.readTree(body);
            if (!"PENDING".equals(node.get("ocrStatus").asText())) return node;
            Thread.sleep(100);
        }
        throw new AssertionError("El OCR no termino en 5 segundos");
    }

    private String register(String email) throws Exception {
        return postJson("/api/v1/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"Test\"}", 201)
                .get("accessToken").asText();
    }

    private JsonNode postJson(String path, String token, String body, int expected) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
