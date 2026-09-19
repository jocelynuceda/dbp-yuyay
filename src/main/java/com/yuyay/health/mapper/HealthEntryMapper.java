package com.yuyay.health.mapper;

import com.yuyay.config.MapStructConfig;
import com.yuyay.health.dto.HealthEntryResponse;
import com.yuyay.health.dto.HealthEntryVersionResponse;
import com.yuyay.health.entity.HealthEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapStructConfig.class, uses = HealthEntryVersionMapper.class)
public interface HealthEntryMapper {

    @Mapping(target = "id", source = "entry.id")
    @Mapping(target = "careSubjectId", source = "entry.careSubject.id")
    @Mapping(target = "categoryCode", source = "entry.category.code")
    @Mapping(target = "categoryName", source = "entry.category.name")
    @Mapping(target = "createdBy", source = "entry.createdBy")
    @Mapping(target = "createdAt", source = "entry.createdAt")
    @Mapping(target = "deletedAt", source = "entry.deletedAt")
    @Mapping(target = "latestVersion", source = "latestVersion")
    HealthEntryResponse toResponse(HealthEntry entry,
                                   HealthEntryVersionResponse latestVersion);
}