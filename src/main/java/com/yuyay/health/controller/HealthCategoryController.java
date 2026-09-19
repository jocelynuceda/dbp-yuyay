package com.yuyay.health.controller;

import com.yuyay.health.dto.HealthCategoryResponse;
import com.yuyay.health.service.HealthCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/health-categories")
@RequiredArgsConstructor
public class HealthCategoryController {

    private final HealthCategoryService service;

    @GetMapping
    public List<HealthCategoryResponse> list() {
        return service.listAll();
    }
}