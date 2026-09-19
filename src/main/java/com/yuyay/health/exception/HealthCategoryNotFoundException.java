package com.yuyay.health.exception;

import com.yuyay.exception.ResourceNotFoundException;

public class HealthCategoryNotFoundException extends ResourceNotFoundException {
    public HealthCategoryNotFoundException(String code) {
        super("HealthCategory not found: " + code);
    }
}