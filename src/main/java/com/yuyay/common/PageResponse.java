package com.yuyay.common;

import org.springframework.data.domain.Page;

import java.util.List;

/** Stable public pagination contract, independent of Spring's Page serialization. */
public record PageResponse<T>(List<T> content, long totalElements, int totalPages, int number, int size) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getTotalElements(), page.getTotalPages(),
                page.getNumber(), page.getSize());
    }
}
