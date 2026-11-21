package com.yuyay.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class GoogleTermsRequiredException extends AuthFlowException {
    private final String currentVersion;

    public GoogleTermsRequiredException(String code, String message, String currentVersion) {
        super(HttpStatus.PRECONDITION_REQUIRED, code, message);
        this.currentVersion = currentVersion;
    }
}
