package com.yuyay.notification.entity;

public enum NotificationType {
    ENTRY_CHANGED,
    INVITATION,
    DELEGATION,
    CAREGIVER_JOINED,
    APPOINTMENT_REMINDER,
    PROFILE_CLAIMED;

    public boolean requiresCareSubjectAccess() {
        return switch (this) {
            case ENTRY_CHANGED, APPOINTMENT_REMINDER -> true;
            case INVITATION, DELEGATION, CAREGIVER_JOINED, PROFILE_CLAIMED -> false;
        };
    }
}
