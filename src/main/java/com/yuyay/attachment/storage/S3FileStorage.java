package com.yuyay.attachment.storage;

import com.yuyay.attachment.exception.FileStorageException;
import com.yuyay.config.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3FileStorage implements FileStorage {
    private final S3Client s3Client;
    private final AwsProperties awsProperties;

    @Override
    public void store(String key, byte[] content, String contentType) {
        if (!StringUtils.hasText(awsProperties.s3Bucket())) {
            throw new FileStorageException("No hay bucket de S3 configurado (AWS_S3_BUCKET)");
        }
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(awsProperties.s3Bucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException e) {
            log.error("Fallo subiendo {} a S3", key, e);
            throw new FileStorageException("No se pudo guardar el archivo");
        }
    }
}
