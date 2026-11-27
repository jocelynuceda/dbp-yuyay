package com.yuyay.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Un dato que pasó la validación de formato pero se rechaza por su contenido (por ejemplo, un DNI
 * que no corresponde al nombre). Responde 400 con el error asociado al campo, como una validación,
 * para que el cliente lo muestre junto al campo y no como un error general.
 */
@Getter
public class FieldRejectedException extends YuyayException {
    private final String field;

    public FieldRejectedException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, message);
        this.field = field;
    }
}
