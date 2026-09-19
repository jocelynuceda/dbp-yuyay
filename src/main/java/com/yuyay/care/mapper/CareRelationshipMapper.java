package com.yuyay.care.mapper;

import com.yuyay.care.dto.CareRelationshipResponse;
import com.yuyay.care.dto.CareUserSummary;
import com.yuyay.care.entity.CareRelationship;
import com.yuyay.config.MapStructConfig;
import com.yuyay.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapStructConfig.class)
public interface CareRelationshipMapper {

    @Mapping(target = "careSubjectId", source = "careSubject.id")
    @Mapping(target = "careSubjectName", source = "careSubject.name")
    @Mapping(target = "user", source = "user")
    CareRelationshipResponse toResponse(CareRelationship relationship);

    CareUserSummary toSummary(User user);
}