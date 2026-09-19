package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends YuyayException {
    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
