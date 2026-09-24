package com.yuyay.attachment.exception;

import com.yuyay.exception.ResourceNotFoundException;

public class AttachmentNotFoundException extends ResourceNotFoundException {
    public AttachmentNotFoundException(Long id) {
        super("Attachment not found: " + id);
    }
}
