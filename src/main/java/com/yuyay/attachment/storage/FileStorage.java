package com.yuyay.attachment.storage;

public interface FileStorage {
    void store(String key, byte[] content, String contentType);
}
