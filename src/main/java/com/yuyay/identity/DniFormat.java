package com.yuyay.identity;

import java.util.regex.Pattern;

/** Un DNI mal formado se rechaza antes de gastar una consulta, que se paga o consume cuota. */
final class DniFormat {

    private static final Pattern EIGHT_DIGITS = Pattern.compile("\\d{8}");

    private DniFormat() {
    }

    static String require(String dni) {
        if (dni == null || !EIGHT_DIGITS.matcher(dni).matches()) {
            // Sin el valor en el mensaje: este texto puede terminar en un log o en una respuesta.
            throw new IllegalArgumentException("El DNI debe tener ocho dígitos");
        }
        return dni;
    }
}
