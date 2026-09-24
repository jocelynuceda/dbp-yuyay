package com.yuyay.attachment.exception;

import com.yuyay.exception.YuyayException;
import org.springframework.http.HttpStatus;

public class InvalidAttachmentException extends YuyayException {
    public InvalidAttachmentException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
