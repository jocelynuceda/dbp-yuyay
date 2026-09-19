package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class DuplicateEmailException extends YuyayException {
    public DuplicateEmailException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
