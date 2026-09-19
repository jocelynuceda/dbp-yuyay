package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class InvalidHealthEntryException extends YuyayException {
    public InvalidHealthEntryException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
