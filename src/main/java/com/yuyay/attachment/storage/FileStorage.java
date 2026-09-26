package com.yuyay.attachment.storage;

public interface FileStorage {
    void store(String key, byte[] content, String contentType);

    /** Borra un objeto ya subido. Se usa para compensar cuando la transaccion que lo registraba falla. */
    void delete(String key);
}
