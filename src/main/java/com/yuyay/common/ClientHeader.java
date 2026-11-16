package com.yuyay.common;

import java.util.regex.Pattern;

/**
 * Cabecera con la que el cliente (web, app Android/iOS y su build) se identifica, solo para depuración.
 * Informativa: la envía el cliente y nunca se confía en ella ni se usa para autorizar.
 */
public final class ClientHeader {
    public static final String NAME = "X-Yuyay-Client";

    /**
     * Atributo de la solicitud con el valor ya saneado. Lo fija {@code AttachmentRequestLogFilter}, que corre antes
     * de Spring Security y por eso lee la cabecera cruda sin que el firewall la rechace; el resto del backend
     * debe leer este atributo y nunca la cabecera.
     */
    public static final String ATTRIBUTE = ClientHeader.class.getName() + ".value";

    private static final String UNAVAILABLE = "unavailable";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._/+-]{1,64}");

    private ClientHeader() {
    }

    /**
     * Devuelve el valor recortado si tiene de 1 a 64 caracteres de {@code [A-Za-z0-9._/+-]};
     * en cualquier otro caso (nulo, vacío, demasiado largo, caracteres raros) devuelve {@code "unavailable"}.
     * Así el valor es seguro de registrar y de guardar (sin saltos de línea ni datos arbitrarios).
     */
    public static String sanitize(String value) {
        if (value == null) return UNAVAILABLE;
        String trimmed = value.trim();
        return SAFE.matcher(trimmed).matches() ? trimmed : UNAVAILABLE;
    }
}
