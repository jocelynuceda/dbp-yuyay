package com.yuyay.attachment.exception;

import com.yuyay.exception.YuyayException;
import org.springframework.http.HttpStatus;

public class FileStorageException extends YuyayException {
    public FileStorageException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }
}
