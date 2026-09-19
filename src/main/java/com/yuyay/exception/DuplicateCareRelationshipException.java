package com.yuyay.exception;

import org.springframework.http.HttpStatus;

public class DuplicateCareRelationshipException extends YuyayException {
    public DuplicateCareRelationshipException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
