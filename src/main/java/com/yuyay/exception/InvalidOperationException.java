package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class InvalidOperationException extends YuyayException {
    public InvalidOperationException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
