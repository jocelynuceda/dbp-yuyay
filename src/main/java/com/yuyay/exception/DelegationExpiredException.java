package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class DelegationExpiredException extends YuyayException {
    public DelegationExpiredException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
