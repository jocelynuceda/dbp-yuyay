package com.yuyay.care.exception;

import com.yuyay.exception.ResourceNotFoundException;

public class CareSubjectNotFoundException extends ResourceNotFoundException {
    public CareSubjectNotFoundException(Long id) {
        super("CareSubject not found: " + id);
    }
}