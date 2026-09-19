package com.yuyay.care.exception;

import com.yuyay.exception.ResourceNotFoundException;

public class CareRelationshipNotFoundException extends ResourceNotFoundException {
    public CareRelationshipNotFoundException(Long id) {
        super("CareRelationship not found: " + id);
    }
}
