package com.yuyay.care.mapper;

import com.yuyay.care.dto.CareSubjectCreateRequest;
import com.yuyay.care.dto.CareSubjectResponse;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.config.MapStructConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapStructConfig.class)
public interface CareSubjectMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "careRelationships", ignore = true)
    @Mapping(target = "healthEntries", ignore = true)
    @Mapping(target = "consultations", ignore = true)
    @Mapping(target = "handoffs", ignore = true)
    @Mapping(target = "delegations", ignore = true)
    @Mapping(target = "accessLogs", ignore = true)
    CareSubject toEntity(CareSubjectCreateRequest request);

    @Mapping(target = "myRole", source = "myRole")
    CareSubjectResponse toResponse(CareSubject subject, CareRole myRole);
}