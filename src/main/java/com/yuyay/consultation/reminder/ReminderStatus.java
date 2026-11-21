package com.yuyay.consultation.reminder;

public enum ReminderStatus {
    /** Reservado antes de enviar el correo: si el servidor cae en ese momento, no se reenvía. */
    PENDING,
    SENT,
    FAILED
}
