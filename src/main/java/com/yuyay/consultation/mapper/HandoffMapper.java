package com.yuyay.consultation.mapper;

import com.yuyay.config.MapStructConfig;
import com.yuyay.consultation.dto.HandoffDetailDTO;
import com.yuyay.consultation.dto.HandoffItemResponseDTO;
import com.yuyay.consultation.dto.HandoffResponseDTO;
import com.yuyay.consultation.entity.Handoff;
import com.yuyay.consultation.entity.HandoffItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapStructConfig.class)
public interface HandoffMapper {

    @Mapping(target = "careSubjectId", source = "careSubject.id")
    @Mapping(target = "createdByUserId", source = "createdBy.id")
    HandoffResponseDTO toResponse(Handoff handoff);

    @Mapping(target = "careSubjectId", source = "careSubject.id")
    @Mapping(target = "createdByUserId", source = "createdBy.id")
    @Mapping(target = "items", source = "items")
    HandoffDetailDTO toDetail(Handoff handoff);

    @Mapping(target = "healthEntryId",
            source = "healthEntryVersion.healthEntry.id")
    @Mapping(target = "healthEntryVersionId",
            source = "healthEntryVersion.id")
    @Mapping(target = "categoryCode",
            source = "healthEntryVersion.healthEntry.category.code")
    @Mapping(target = "versionNumber",
            source = "healthEntryVersion.versionNumber")
    @Mapping(target = "changeType",
            source = "healthEntryVersion.changeType")
    @Mapping(target = "title",
            source = "healthEntryVersion.title")
    @Mapping(target = "details",
            source = "healthEntryVersion.details")
    @Mapping(target = "dose",
            source = "healthEntryVersion.dose")
    @Mapping(target = "frequency",
            source = "healthEntryVersion.frequency")
    @Mapping(target = "occurredOn",
            source = "healthEntryVersion.occurredOn")
    @Mapping(target = "confidenceLevel",
            source = "healthEntryVersion.confidenceLevel")
    @Mapping(target = "notes",
            source = "healthEntryVersion.notes")
    @Mapping(target = "declaredAt",
            source = "healthEntryVersion.declaredAt")
    HandoffItemResponseDTO toItemResponse(HandoffItem item);
}