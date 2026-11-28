package com.yuyay.medication.entity;

/**
 * {@code TAKEN} y {@code SKIPPED} se marcan a tiempo (desde 2 h antes hasta 30 min después). Pasado ese
 * plazo solo se puede marcar {@code LATE}: así una toma que sí se dio no queda como «no tomada» y nadie
 * repite la dosis por error.
 */
public enum DoseStatus {
    TAKEN,
    SKIPPED,
    LATE
}
