package com.yuyay.event;

import com.yuyay.audit.entity.AccessAction;

public record AccessRecordedEvent(
        Long careSubjectId,
        Long userId,
        Long delegationId,
        AccessAction action,
        String targetType,
        Long targetId,
        String visitorName,
        String visitorRole
) {
    public static AccessRecordedEvent byUser(Long careSubjectId, Long userId, AccessAction action, String targetType, Long targetId) {
        return new AccessRecordedEvent(careSubjectId, userId, null, action, targetType, targetId, null, null);
    }

    public static AccessRecordedEvent byDelegation(Long careSubjectId, Long delegationId, AccessAction action, String targetType, Long targetId) {
        return new AccessRecordedEvent(careSubjectId, null, delegationId, action, targetType, targetId, null, null);
    }

    public static AccessRecordedEvent byVisitor(Long careSubjectId, AccessAction action, String targetType, Long targetId, String visitorName, String visitorRole) {
        return new AccessRecordedEvent(careSubjectId, null, null, action, targetType, targetId, visitorName, visitorRole);
    }
}
