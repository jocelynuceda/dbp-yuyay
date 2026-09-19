package com.yuyay.event;

import com.yuyay.health.entity.ChangeType;

public record HealthEntryChangedEvent(Long healthEntryId, Long careSubjectId, Long changedByUserId, ChangeType changeType) {}
