package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenCareSubjectAccessException extends YuyayException {
    public ForbiddenCareSubjectAccessException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
