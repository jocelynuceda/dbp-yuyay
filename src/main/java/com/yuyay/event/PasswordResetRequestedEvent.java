package com.yuyay.event;

public record PasswordResetRequestedEvent(String email, String userName, String code, long minutesValid) {}
