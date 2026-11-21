package com.yuyay.notification.email;

/** El proveedor no aceptó el correo. Quien envía decide si reintenta o solo lo registra. */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
