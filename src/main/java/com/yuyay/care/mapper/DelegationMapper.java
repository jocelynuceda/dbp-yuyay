package com.yuyay.care.mapper;

import com.yuyay.care.dto.DelegationResponse;
import com.yuyay.care.entity.Delegation;
import com.yuyay.config.MapStructConfig;
import com.yuyay.health.entity.HealthCategory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Set;
import java.util.stream.Collectors;

@Mapper(config = MapStructConfig.class)
public interface DelegationMapper {

    @Mapping(target = "careSubjectId", source = "careSubject.id")
    @Mapping(target = "careSubjectName", source = "careSubject.name")
    @Mapping(target = "categoryCodes", source = "categories")
    @Mapping(target = "active", expression = "java(delegation.isValidNow())")
    DelegationResponse toResponse(Delegation delegation);

    default Set<String> toCodes(Set<HealthCategory> categories) {
        return categories.stream().map(HealthCategory::getCode).collect(Collectors.toSet());
    }
}
