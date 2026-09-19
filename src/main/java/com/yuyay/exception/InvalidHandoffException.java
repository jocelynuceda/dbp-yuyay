package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class InvalidHandoffException extends YuyayException {
    public InvalidHandoffException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
