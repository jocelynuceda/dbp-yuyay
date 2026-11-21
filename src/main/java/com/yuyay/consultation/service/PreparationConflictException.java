package com.yuyay.consultation.service;

import com.yuyay.exception.YuyayException;
import org.springframework.http.HttpStatus;

public class PreparationConflictException extends YuyayException {
    public PreparationConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
