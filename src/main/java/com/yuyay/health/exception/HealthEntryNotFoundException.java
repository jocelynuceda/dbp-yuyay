package com.yuyay.health.exception;

import com.yuyay.exception.ResourceNotFoundException;

public class HealthEntryNotFoundException extends ResourceNotFoundException {
    public HealthEntryNotFoundException(Long id) {
        super("HealthEntry not found: " + id);
    }
}