package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class InvalidTokenException extends YuyayException {
    public InvalidTokenException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
