package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends YuyayException {
    public InvalidCredentialsException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
