package com.yuyay.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class GoogleRegistrationRequiredException extends AuthFlowException {
    private final String currentVersion;

    public GoogleRegistrationRequiredException(String code, String message, String currentVersion) {
        super(HttpStatus.PRECONDITION_REQUIRED, code, message);
        this.currentVersion = currentVersion;
    }
}
