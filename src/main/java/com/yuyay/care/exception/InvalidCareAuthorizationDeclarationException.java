package com.yuyay.care.exception;

import com.yuyay.exception.YuyayException;
import org.springframework.http.HttpStatus;

public class InvalidCareAuthorizationDeclarationException extends YuyayException {
    public InvalidCareAuthorizationDeclarationException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
