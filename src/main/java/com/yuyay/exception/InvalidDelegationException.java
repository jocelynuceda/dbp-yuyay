package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class InvalidDelegationException extends YuyayException {
    public InvalidDelegationException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
