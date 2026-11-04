package com.yuyay.attachment.mapper;

import com.yuyay.attachment.dto.AttachmentResponse;
import com.yuyay.attachment.entity.Attachment;
import com.yuyay.config.MapStructConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(config = MapStructConfig.class)
public interface AttachmentMapper {
    @Mapping(target = "careSubjectId", source = "careSubject.id")
    @Mapping(target = "consultationId", source = "consultation.id")
    @Mapping(target = "uploadedById", source = "uploadedBy.id")
    @Mapping(target = "uploadedByName", source = "uploadedBy.name")
    @Mapping(target = "ocrError", source = "ocrError", qualifiedByName = "safeOcrError")
    AttachmentResponse toResponse(Attachment attachment);

    /** También protege filas FAILED anteriores que puedan contener mensajes crudos del SDK. */
    @Named("safeOcrError")
    default String safeOcrError(String error) {
        return error == null ? null : "No se pudo extraer el texto. Puedes reintentar el OCR.";
    }
}
