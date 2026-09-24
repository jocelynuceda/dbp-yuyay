package com.yuyay.attachment.mapper;

import com.yuyay.attachment.dto.AttachmentResponse;
import com.yuyay.attachment.entity.Attachment;
import com.yuyay.config.MapStructConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapStructConfig.class)
public interface AttachmentMapper {
    @Mapping(target = "careSubjectId", source = "careSubject.id")
    @Mapping(target = "consultationId", source = "consultation.id")
    @Mapping(target = "uploadedById", source = "uploadedBy.id")
    @Mapping(target = "uploadedByName", source = "uploadedBy.name")
    AttachmentResponse toResponse(Attachment attachment);
}
