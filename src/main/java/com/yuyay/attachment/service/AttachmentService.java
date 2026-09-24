package com.yuyay.attachment.service;

import com.yuyay.attachment.dto.AttachmentResponse;
import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.exception.AttachmentNotFoundException;
import com.yuyay.attachment.exception.InvalidAttachmentException;
import com.yuyay.attachment.mapper.AttachmentMapper;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.attachment.storage.FileStorage;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.exception.CareSubjectNotFoundException;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.consultation.entity.Consultation;
import com.yuyay.consultation.repository.ConsultationRepository;
import com.yuyay.event.AttachmentUploadedEvent;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class AttachmentService {
    static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "application/pdf", ".pdf");

    private final AttachmentRepository attachmentRepo;
    private final CareSubjectRepository careSubjectRepo;
    private final ConsultationRepository consultationRepo;
    private final UserRepository userRepo;
    private final AttachmentMapper mapper;
    private final FileStorage fileStorage;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;
    private final ApplicationEventPublisher eventPublisher;

    public AttachmentResponse upload(Long subjectId, MultipartFile file, Long consultationId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);
        String extension = validate(file);

        CareSubject subject = careSubjectRepo.findById(subjectId)
                .orElseThrow(() -> new CareSubjectNotFoundException(subjectId));
        User me = userRepo.findById(meId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        String key = "care-subjects/" + subjectId + "/" + UUID.randomUUID() + extension;
        fileStorage.store(key, readBytes(file), file.getContentType());

        Attachment attachment = attachmentRepo.save(Attachment.builder()
                .careSubject(subject)
                .consultation(resolveConsultation(subjectId, consultationId))
                .uploadedBy(me)
                .originalFilename(cleanFilename(file.getOriginalFilename()))
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .storageKey(key)
                .ocrStatus(OcrStatus.PENDING)
                .build());

        eventPublisher.publishEvent(new AttachmentUploadedEvent(attachment.getId()));
        return mapper.toResponse(attachment);
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(Long subjectId) {
        authorizationService.requireAccess(currentUserService.getCurrentUserId(), subjectId);
        return attachmentRepo.findByCareSubjectIdOrderByUploadedAtDesc(subjectId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AttachmentResponse get(Long subjectId, Long attachmentId) {
        authorizationService.requireAccess(currentUserService.getCurrentUserId(), subjectId);
        return mapper.toResponse(load(subjectId, attachmentId));
    }

    public AttachmentResponse retryOcr(Long subjectId, Long attachmentId) {
        authorizationService.requireAccess(currentUserService.getCurrentUserId(), subjectId);
        Attachment attachment = load(subjectId, attachmentId);
        if (attachment.getOcrStatus() != OcrStatus.FAILED) {
            throw new InvalidOperationException("Solo se puede reintentar un OCR que falló");
        }
        attachment.setOcrStatus(OcrStatus.PENDING);
        attachment.setOcrError(null);
        attachment.setOcrCompletedAt(null);
        eventPublisher.publishEvent(new AttachmentUploadedEvent(attachment.getId()));
        return mapper.toResponse(attachment);
    }

    private Attachment load(Long subjectId, Long attachmentId) {
        return attachmentRepo.findByIdAndCareSubjectId(attachmentId, subjectId)
                .orElseThrow(() -> new AttachmentNotFoundException(attachmentId));
    }

    private Consultation resolveConsultation(Long subjectId, Long consultationId) {
        if (consultationId == null) return null;
        return consultationRepo.findByIdAndCareSubjectId(consultationId, subjectId)
                .orElseThrow(() -> new InvalidAttachmentException("La consulta no existe o no pertenece a esta persona"));
    }

    private String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidAttachmentException("El archivo está vacío");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new InvalidAttachmentException("El archivo supera el tamaño máximo permitido (10 MB)");
        }
        String extension = ALLOWED_TYPES.get(file.getContentType());
        if (extension == null) {
            throw new InvalidAttachmentException("Formato no soportado: usa JPG, PNG o PDF de una página");
        }
        return extension;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidAttachmentException("No se pudo leer el archivo");
        }
    }

    private String cleanFilename(String originalFilename) {
        String name = StringUtils.getFilename(StringUtils.cleanPath(
                originalFilename == null ? "" : originalFilename.replace('\\', '/')));
        if (!StringUtils.hasText(name)) return "archivo";
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }
}
