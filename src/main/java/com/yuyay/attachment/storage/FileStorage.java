package com.yuyay.attachment.storage;

import com.yuyay.attachment.dto.AttachmentAccessResponse;

import java.time.Duration;

public interface FileStorage {
    void store(String key, byte[] content, String contentType);

    /** Borra un objeto ya subido. Se usa para compensar cuando la transaccion que lo registraba falla. */
    void delete(String key);

    /** Borrado explícito: debe fallar si S3 no confirma la operación. */
    void deleteRequired(String key);

    AttachmentAccessResponse createAccess(String key, String contentType, Duration ttl);
}
