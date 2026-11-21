package com.yuyay.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AuthFlowException extends YuyayException {
    private final String code;

    public AuthFlowException(HttpStatus status, String code, String message) {
        super(status, message);
        this.code = code;
    }
}
