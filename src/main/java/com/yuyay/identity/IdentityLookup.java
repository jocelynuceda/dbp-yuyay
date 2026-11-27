package com.yuyay.identity;

/** Resultado del contraste. La indisponibilidad del directorio no prueba que el DNI sea inexistente. */
public sealed interface IdentityLookup {
    record Found(IdentityRecord person) implements IdentityLookup {}
    record NotFound() implements IdentityLookup {}
    record Unavailable(String reason) implements IdentityLookup {}
}
