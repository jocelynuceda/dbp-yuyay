package com.yuyay.user.deletion.dto;

import java.time.Instant;
import java.util.List;

/**
 * Lo que se pierde al eliminar la cuenta, sin eliminar nada. El cliente la muestra y devuelve los
 * identificadores de {@code profilesToDelete} en el borrado: el servidor no acepta una confirmación
 * que no coincida con esta lista, porque un cliente puede no haber mostrado el aviso.
 */
public record AccountDeletionPreview(
        List<ProfileAtRisk> profilesToDelete,
        List<ProfileKept> profilesKept,
        List<String> blockers,
        long declarationsKept,
        String confirmationWord,
        Instant checkedAt
) {
    public boolean canDelete() {
        return blockers.isEmpty();
    }
}
