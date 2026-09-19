package com.yuyay.event;

public record HealthEntryChangedEvent(
        Long healthEntryId,
        Long careSubjectId,
        Long actorUserId,
        String action,
        String changeDescription,
        String careSubjectName
) {}
