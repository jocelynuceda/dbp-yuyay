package com.yuyay.attachment;

import com.yuyay.attachment.exception.FileStorageException;
import com.yuyay.attachment.storage.S3FileStorage;
import com.yuyay.config.AwsProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Contrato con S3: bucket, key y Content-Type correctos, y errores del SDK traducidos a 502 sin filtrar detalles. */
class S3FileStorageTest {

    private final S3Client client = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);

    @Test
    void putsObjectInConfiguredBucketWithKeyAndContentType() {
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"), presigner);

        storage.store("care-subjects/7/abc.jpg", new byte[]{1, 2, 3}, "image/jpeg");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(RequestBody.class));
        assertEquals("yuyay-bucket", captor.getValue().bucket());
        assertEquals("care-subjects/7/abc.jpg", captor.getValue().key());
        assertEquals("image/jpeg", captor.getValue().contentType());
    }

    @Test
    void failsFastWhenBucketIsNotConfigured() {
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", ""), presigner);

        assertThrows(FileStorageException.class, () -> storage.store("k.jpg", new byte[]{1}, "image/jpeg"));
        verifyNoInteractions(client);
    }

    @Test
    void wrapsSdkErrorsWithoutLeakingAwsDetails() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: s3.amazonaws.com"));
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"), presigner);

        FileStorageException ex = assertThrows(FileStorageException.class,
                () -> storage.store("k.jpg", new byte[]{1}, "image/jpeg"));
        assertEquals("No se pudo guardar el archivo", ex.getMessage());
    }

    @Test
    void deletesObjectFromConfiguredBucket() {
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"), presigner);

        storage.delete("care-subjects/7/abc.jpg");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(captor.capture());
        assertEquals("yuyay-bucket", captor.getValue().bucket());
        assertEquals("care-subjects/7/abc.jpg", captor.getValue().key());
    }

    @Test
    void deleteFailureDoesNotHideTheOriginalError() {
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"));
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"), presigner);

        assertDoesNotThrow(() -> storage.delete("care-subjects/7/abc.jpg"));
    }
    @Test
    void requiredDeletePropagatesSafeFailureAndMissingBucket() {
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(SdkClientException.create("private AWS endpoint and request details"));
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"), presigner);
        FileStorageException error = assertThrows(FileStorageException.class, () -> storage.deleteRequired("key"));
        assertEquals("No se pudo eliminar el archivo", error.getMessage());
        S3FileStorage unconfigured = new S3FileStorage(client, new AwsProperties("us-east-1", ""), presigner);
        assertThrows(FileStorageException.class, () -> unconfigured.deleteRequired("key"));
    }

    @Test
    void signsPrivateGetWithFiveMinuteExpiryAndNoStoreWithoutNetwork() {
        try (S3Presigner localSigner = S3Presigner.builder().region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
                .build()) {
            S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"), localSigner);
            Instant before = Instant.now();
            var access = storage.createAccess("care-subjects/7/document.pdf", "application/pdf", Duration.ofMinutes(5));
            Instant after = Instant.now();
            assertTrue(access.expiresAt().isAfter(before.plusSeconds(299)));
            assertTrue(access.expiresAt().isBefore(after.plusSeconds(301)));
            var uri = java.net.URI.create(access.url());
            assertEquals("https", uri.getScheme());
            assertTrue(uri.getHost().startsWith("yuyay-bucket.s3"));
            assertTrue(uri.getHost().endsWith(".amazonaws.com"));
            assertEquals("/care-subjects/7/document.pdf", uri.getPath());
            assertTrue(access.url().contains("X-Amz-Expires=300"));
            assertTrue(access.url().contains("response-cache-control=no-store"));
            assertTrue(access.url().contains("response-content-type=application%2Fpdf"));
            verifyNoInteractions(client);
        }
    }

    @Test
    void signingFailureDoesNotExposeCredentialsOrAwsDetails() {
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(SdkClientException.create("sensitive credential provider details"));
        var storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"), presigner);
        var error = assertThrows(FileStorageException.class,
                () -> storage.createAccess("key", "image/png", Duration.ofMinutes(5)));
        assertEquals("No se pudo abrir el archivo", error.getMessage());
    }
}
