package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class InvalidPaginationException extends YuyayException {
    public InvalidPaginationException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
