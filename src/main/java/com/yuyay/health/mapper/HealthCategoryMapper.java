package com.yuyay.health.mapper;

import com.yuyay.config.MapStructConfig;
import com.yuyay.health.dto.HealthCategoryResponse;
import com.yuyay.health.entity.HealthCategory;
import org.mapstruct.Mapper;

@Mapper(config = MapStructConfig.class)
public interface HealthCategoryMapper {
    HealthCategoryResponse toResponse(HealthCategory category);
}