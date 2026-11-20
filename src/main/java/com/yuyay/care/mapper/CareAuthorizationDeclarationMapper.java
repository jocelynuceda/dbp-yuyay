package com.yuyay.care.mapper;

import com.yuyay.care.dto.CareAuthorizationDeclarationResponse;
import com.yuyay.care.entity.CareAuthorizationDeclaration;
import com.yuyay.config.MapStructConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapStructConfig.class)
public interface CareAuthorizationDeclarationMapper {
    @Mapping(target = "careSubjectId", source = "careSubject.id")
    @Mapping(target = "declaredByUserId", source = "declaredBy.id")
    CareAuthorizationDeclarationResponse toResponse(CareAuthorizationDeclaration declaration);
}
