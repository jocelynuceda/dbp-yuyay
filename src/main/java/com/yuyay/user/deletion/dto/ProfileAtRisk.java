package com.yuyay.user.deletion.dto;

/**
 * Un perfil que se pierde si la cuenta se elimina. {@code hasHistory} es lo que decide si el
 * borrado se puede hacer: hoy un perfil con historial de salud no se puede eliminar, así que hay
 * que entregarlo antes (§3.4) o vaciarlo. {@code documentCount}: documentos y notas de voz que se
 * borran con el perfil.
 */
public record ProfileAtRisk(
        Long id,
        String name,
        long entryCount,
        boolean hasHistory,
        boolean isOwnProfile,
        long documentCount
) {
}
