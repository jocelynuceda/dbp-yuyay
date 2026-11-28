package com.yuyay.care.dto;

public record SelfProfileStatusResponse(Status status) {
    public enum Status { NONE, ACCESSIBLE, INACCESSIBLE }
}
