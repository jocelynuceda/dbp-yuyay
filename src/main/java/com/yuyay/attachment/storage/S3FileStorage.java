package com.yuyay.attachment.storage;

import com.yuyay.attachment.dto.AttachmentAccessResponse;
import com.yuyay.attachment.exception.FileStorageException;
import com.yuyay.config.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3FileStorage implements FileStorage {
    private final S3Client s3Client;
    private final AwsProperties awsProperties;
    private final S3Presigner presigner;

    @Override
    public void store(String key, byte[] content, String contentType) {
        requireBucket();
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(awsProperties.s3Bucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException e) {
            log.warn("Fallo guardando un adjunto en S3 ({})", e.getClass().getSimpleName());
            throw new FileStorageException("No se pudo guardar el archivo");
        }
    }

    /** Compensación de rollback: nunca oculta el error que causó el rollback. */
    @Override
    public void delete(String key) {
        try {
            deleteRequired(key);
        } catch (FileStorageException e) {
            log.warn("No se pudo limpiar un adjunto en S3 tras un rollback");
        }
    }

    @Override
    public void deleteRequired(String key) {
        requireBucket();
        try {
            // DeleteObject es idempotente: un objeto ya ausente también cuenta como eliminado.
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(awsProperties.s3Bucket())
                    .key(key)
                    .build());
        } catch (SdkException e) {
            log.warn("Fallo borrando un adjunto en S3 ({})", e.getClass().getSimpleName());
            throw new FileStorageException("No se pudo eliminar el archivo");
        }
    }

    @Override
    public AttachmentAccessResponse createAccess(String key, String contentType, Duration ttl) {
        requireBucket();
        try {
            var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(awsProperties.s3Bucket())
                            .key(key)
                            .responseContentType(contentType)
                            .responseContentDisposition("inline")
                            .responseCacheControl("no-store")
                            .build())
                    .build());
            return new AttachmentAccessResponse(signed.url().toExternalForm(), signed.expiration());
        } catch (SdkException e) {
            log.warn("Fallo creando acceso temporal a un adjunto ({})", e.getClass().getSimpleName());
            throw new FileStorageException("No se pudo abrir el archivo");
        }
    }

    private void requireBucket() {
        if (!StringUtils.hasText(awsProperties.s3Bucket())) {
            throw new FileStorageException("No hay almacenamiento de archivos configurado");
        }
    }
}
