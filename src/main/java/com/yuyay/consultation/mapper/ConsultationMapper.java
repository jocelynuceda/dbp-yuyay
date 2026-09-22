package com.yuyay.consultation.mapper;

import com.yuyay.config.MapStructConfig;
import com.yuyay.consultation.dto.ConsultationDetailDTO;
import com.yuyay.consultation.dto.ConsultationResponseDTO;
import com.yuyay.consultation.entity.Consultation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapStructConfig.class)
public interface ConsultationMapper {

    @Mapping(target = "careSubjectId", source = "careSubject.id")
    @Mapping(target = "createdByUserId", source = "createdBy.id")
    ConsultationResponseDTO toResponse(Consultation consultation);

    @Mapping(target = "careSubjectId", source = "careSubject.id")
    @Mapping(target = "careSubjectName", source = "careSubject.name")
    @Mapping(target = "createdByUserId", source = "createdBy.id")
    ConsultationDetailDTO toDetail(Consultation consultation);
}