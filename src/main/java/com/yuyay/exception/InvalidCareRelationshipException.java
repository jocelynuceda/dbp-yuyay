package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class InvalidCareRelationshipException extends YuyayException {
    public InvalidCareRelationshipException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
