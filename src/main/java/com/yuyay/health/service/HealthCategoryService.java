package com.yuyay.health.service;

import com.yuyay.health.dto.HealthCategoryResponse;
import com.yuyay.health.mapper.HealthCategoryMapper;
import com.yuyay.health.repository.HealthCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HealthCategoryService {

    private final HealthCategoryRepository repository;
    private final HealthCategoryMapper mapper;

    public List<HealthCategoryResponse> listAll() {
        return repository.findAll().stream()
                .map(mapper::toResponse)
                .toList();
    }
}