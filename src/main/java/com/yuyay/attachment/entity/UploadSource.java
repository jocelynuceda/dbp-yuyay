package com.yuyay.attachment.entity;

import java.util.Locale;

/** How the client says a document was picked. Informational only; never trusted or used for decisions. */
public enum UploadSource {
    CAMERA, GALLERY, FILES, WEB;

    /** Maps the lowercase request param (camera, gallery, files, web); null for null, blank or unknown values. */
    public static UploadSource fromParam(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (UploadSource source : values()) {
            if (source.name().toLowerCase(Locale.ROOT).equals(normalized)) return source;
        }
        return null;
    }
}
