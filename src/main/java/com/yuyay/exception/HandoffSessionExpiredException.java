package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class HandoffSessionExpiredException extends YuyayException {
    public HandoffSessionExpiredException(String message) {
        super(HttpStatus.GONE, message);
    }
}
