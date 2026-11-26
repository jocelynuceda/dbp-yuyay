package com.yuyay.security.handoff;

import com.yuyay.exception.AuthFlowException;
import org.springframework.http.HttpStatus;

/** Excepción específica: los intentos inválidos deben confirmarse en la transacción de seguridad. */
public class HandoffCodeException extends AuthFlowException {
    public HandoffCodeException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
