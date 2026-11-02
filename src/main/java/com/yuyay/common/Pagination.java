package com.yuyay.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import com.yuyay.exception.InvalidPaginationException;

import java.util.Set;

public final class Pagination {
    private Pagination() {}

    public static Pageable stable(Pageable pageable, String timestamp) {
        Set<String> allowed = Set.of("id", timestamp);
        Sort sort = pageable.getSort();
        if (sort.stream().anyMatch(order -> !allowed.contains(order.getProperty()))) {
            throw new InvalidPaginationException("Orden permitido: id, " + timestamp);
        }
        if (sort.isUnsorted()) sort = Sort.by(Sort.Direction.DESC, timestamp);
        if (sort.getOrderFor("id") == null) sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));
        return PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100), sort);
    }
}
