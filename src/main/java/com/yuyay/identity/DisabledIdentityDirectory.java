package com.yuyay.identity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Sin credencial configurada no se consulta nada: toda consulta queda «no disponible». */
@Slf4j
@Component
@ConditionalOnExpression("'${app.reniec.api-token:}'.isEmpty()")
public class DisabledIdentityDirectory implements IdentityDirectory {

    static final String REASON = "Consulta de DNI deshabilitada (sin RENIEC_API_TOKEN)";

    @Override
    public IdentityLookup findByDni(String dni) {
        DniFormat.require(dni);
        log.debug(REASON);
        return new IdentityLookup.Unavailable(REASON);
    }

    @Override
    public boolean isConfigured() {
        return false;
    }
}
