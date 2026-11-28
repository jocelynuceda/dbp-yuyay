package com.yuyay.medication.service;

import com.yuyay.exception.InvalidOperationException;
import com.yuyay.medication.entity.DoseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Plazo para marcar una toma: desde 2 h antes, a tiempo hasta 30 min después")
class MarkWindowTest {
    private static final LocalDateTime DOSE = LocalDateTime.of(2026, 11, 27, 8, 0);

    private static void ok(int minutesFromDose, DoseStatus status) {
        assertDoesNotThrow(() -> MedicationScheduleService.checkMarkWindow(DOSE, DOSE.plusMinutes(minutesFromDose), status));
    }

    private static void rejected(int minutesFromDose, DoseStatus status) {
        assertThrows(InvalidOperationException.class,
                () -> MedicationScheduleService.checkMarkWindow(DOSE, DOSE.plusMinutes(minutesFromDose), status));
    }

    @Test
    void beforeTheWindowNothingCanBeMarked() {
        rejected(-121, DoseStatus.TAKEN);
        rejected(-121, DoseStatus.LATE);
        rejected(-24 * 60, DoseStatus.SKIPPED);
    }

    @Test
    void fromTwoHoursBeforeToThirtyMinutesAfterItIsOnTime() {
        ok(-120, DoseStatus.TAKEN);
        ok(0, DoseStatus.TAKEN);
        ok(30, DoseStatus.TAKEN);
        ok(10, DoseStatus.SKIPPED);
        rejected(30, DoseStatus.LATE);
    }

    @Test
    void afterThirtyMinutesOnlyLate() {
        rejected(31, DoseStatus.TAKEN);
        rejected(31, DoseStatus.SKIPPED);
        ok(31, DoseStatus.LATE);
        ok(3 * 24 * 60, DoseStatus.LATE);
    }
}
