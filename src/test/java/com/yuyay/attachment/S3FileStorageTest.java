package com.yuyay.attachment;

import com.yuyay.attachment.exception.FileStorageException;
import com.yuyay.attachment.storage.S3FileStorage;
import com.yuyay.config.AwsProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Contrato con S3: bucket, key y Content-Type correctos, y errores del SDK traducidos a 502 sin filtrar detalles. */
class S3FileStorageTest {

    private final S3Client client = mock(S3Client.class);

    @Test
    void putsObjectInConfiguredBucketWithKeyAndContentType() {
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"));

        storage.store("care-subjects/7/abc.jpg", new byte[]{1, 2, 3}, "image/jpeg");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(RequestBody.class));
        assertEquals("yuyay-bucket", captor.getValue().bucket());
        assertEquals("care-subjects/7/abc.jpg", captor.getValue().key());
        assertEquals("image/jpeg", captor.getValue().contentType());
    }

    @Test
    void failsFastWhenBucketIsNotConfigured() {
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", ""));

        assertThrows(FileStorageException.class, () -> storage.store("k.jpg", new byte[]{1}, "image/jpeg"));
        verifyNoInteractions(client);
    }

    @Test
    void wrapsSdkErrorsWithoutLeakingAwsDetails() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: s3.amazonaws.com"));
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"));

        FileStorageException ex = assertThrows(FileStorageException.class,
                () -> storage.store("k.jpg", new byte[]{1}, "image/jpeg"));
        assertEquals("No se pudo guardar el archivo", ex.getMessage());
    }

    @Test
    void deletesObjectFromConfiguredBucket() {
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"));

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
        S3FileStorage storage = new S3FileStorage(client, new AwsProperties("us-east-1", "yuyay-bucket"));

        assertDoesNotThrow(() -> storage.delete("care-subjects/7/abc.jpg"));
    }
}
