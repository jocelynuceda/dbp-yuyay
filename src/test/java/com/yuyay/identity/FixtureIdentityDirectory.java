package com.yuyay.identity;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Directorio sintético exclusivo del classpath de pruebas de integración. */
@Component
@Profile("test")
@Primary
class FixtureIdentityDirectory implements IdentityDirectory {
    @Override
    public IdentityLookup findByDni(String dni) {
        return "12345678".equals(dni)
                ? new IdentityLookup.Found(new IdentityRecord("PERSONA", "SINTETICA", "", LocalDate.of(1990, 1, 1)))
                : new IdentityLookup.NotFound();
    }
}
