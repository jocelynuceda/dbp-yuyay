package com.yuyay.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public abstract class YuyayException extends RuntimeException {
    private final HttpStatus status;

    protected YuyayException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
