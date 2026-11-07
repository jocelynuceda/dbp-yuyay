package com.yuyay.attachment.dto;

public record AttachmentContent(byte[] bytes, String contentType, String filename) {}
