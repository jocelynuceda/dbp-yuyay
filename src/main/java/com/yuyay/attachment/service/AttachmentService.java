package com.yuyay.attachment.service;

import com.yuyay.attachment.dto.AttachmentAccessResponse;
import com.yuyay.attachment.dto.AttachmentContent;
import com.yuyay.attachment.dto.AttachmentResponse;
import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.exception.AttachmentNotFoundException;
import com.yuyay.attachment.exception.InvalidAttachmentException;
import com.yuyay.attachment.mapper.AttachmentMapper;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.attachment.storage.FileStorage;
import com.yuyay.audit.entity.AccessAction;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.exception.CareSubjectNotFoundException;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.consultation.entity.Consultation;
import com.yuyay.consultation.repository.ConsultationRepository;
import com.yuyay.event.AccessRecordedEvent;
import com.yuyay.event.AttachmentUploadedEvent;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
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
            "application/pdf", ".pdf",
            "audio/mp4", ".m4a",
            "audio/x-m4a", ".m4a",
            "audio/webm", ".webm");

    private final AttachmentRepository attachmentRepo;
    private final CareSubjectRepository careSubjectRepo;
    private final ConsultationRepository consultationRepo;
    private final HealthEntryVersionRepository versionRepo;
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
        byte[] content = readBytes(file);
        requireMatchingSignature(file.getContentType(), content);

        CareSubject subject = careSubjectRepo.findById(subjectId)
                .orElseThrow(() -> new CareSubjectNotFoundException(subjectId));
        User me = userRepo.findById(meId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        // Todo lo que puede fallar con 4xx se valida ANTES de subir a S3: así no quedan archivos huérfanos.
        Consultation consultation = resolveConsultation(subjectId, consultationId);

        String key = "care-subjects/" + subjectId + "/" + UUID.randomUUID() + extension;
        fileStorage.store(key, content, file.getContentType());
        deleteFromStorageIfRolledBack(key);

        // Los audios no pasan por el OCR. Se marcan DONE y no con un estado nuevo porque la columna tiene
        // un CHECK con los valores actuales y ddl-auto=update no lo modifica en las bases existentes.
        boolean audio = file.getContentType().startsWith("audio/");
        Attachment attachment = attachmentRepo.save(Attachment.builder()
                .careSubject(subject)
                .consultation(consultation)
                .uploadedBy(me)
                .originalFilename(cleanFilename(file.getOriginalFilename()))
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .storageKey(key)
                .ocrStatus(audio ? OcrStatus.DONE : OcrStatus.PENDING)
                .build());

        if (!audio) eventPublisher.publishEvent(new AttachmentUploadedEvent(attachment.getId()));
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

    @Transactional(readOnly = true)
    public AttachmentAccessResponse access(Long subjectId, Long attachmentId) {
        authorizationService.requireAccess(currentUserService.getCurrentUserId(), subjectId);
        Attachment attachment = load(subjectId, attachmentId);
        return fileStorage.createAccess(attachment.getStorageKey(), attachment.getContentType(), Duration.ofMinutes(5));
    }

    @Transactional(readOnly = true)
    public AttachmentContent content(Long subjectId, Long attachmentId) {
        authorizationService.requireAccess(currentUserService.getCurrentUserId(), subjectId);
        Attachment attachment = load(subjectId, attachmentId);
        return new AttachmentContent(fileStorage.load(attachment.getStorageKey()),
                attachment.getContentType(), attachment.getOriginalFilename());
    }

    /** Lo borra quien lo subió o el principal. Si respalda un registro se conserva: el historial no se reescribe. */
    public void delete(Long subjectId, Long attachmentId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);
        Attachment attachment = load(subjectId, attachmentId);
        if (!attachment.getUploadedBy().getId().equals(meId)) {
            authorizationService.requireRole(meId, subjectId, CareRole.PRINCIPAL);
        }
        if (attachment.getOcrStatus() == OcrStatus.PENDING) {
            throw new InvalidOperationException("Espera a que termine la lectura del documento");
        }
        if (versionRepo.existsBySourceAttachmentId(attachmentId)) {
            throw new InvalidOperationException("Este archivo respalda un registro de salud y no se puede borrar");
        }
        String key = attachment.getStorageKey();
        attachmentRepo.delete(attachment);
        eventPublisher.publishEvent(
                AccessRecordedEvent.byUser(subjectId, meId, AccessAction.DELETE, "ATTACHMENT", attachmentId));
        deleteFromStorageAfterCommit(key);
    }

    public AttachmentResponse retryOcr(Long subjectId, Long attachmentId) {
        authorizationService.requireAccess(currentUserService.getCurrentUserId(), subjectId);
        Attachment attachment = loadForUpdate(subjectId, attachmentId);
        if (attachment.getOcrStatus() != OcrStatus.FAILED) {
            throw new InvalidOperationException("Solo se puede reintentar un OCR que falló");
        }
        attachment.setOcrStatus(OcrStatus.PENDING);
        attachment.setOcrError(null);
        attachment.setOcrCompletedAt(null);
        eventPublisher.publishEvent(new AttachmentUploadedEvent(attachment.getId()));
        return mapper.toResponse(attachment);
    }

    /**
     * S3 no participa en la transaccion de la base de datos: si algo falla despues de subir
     * (el INSERT o el propio commit), la foto quedaria en el bucket sin fila que la referencie.
     * Se registra un callback que, solo si la transaccion termina en rollback, borra el objeto.
     */
    private void deleteFromStorageIfRolledBack(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    fileStorage.delete(key);
                }
            }
        });
    }

    private void deleteFromStorageAfterCommit(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fileStorage.delete(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileStorage.delete(key);
            }
        });
    }

    private Attachment load(Long subjectId, Long attachmentId) {
        return attachmentRepo.findByIdAndCareSubjectId(attachmentId, subjectId)
                .orElseThrow(() -> new AttachmentNotFoundException(attachmentId));
    }

    private Attachment loadForUpdate(Long subjectId, Long attachmentId) {
        return attachmentRepo.findForUpdateByIdAndCareSubjectId(attachmentId, subjectId)
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
        String contentType = file.getContentType();
        String extension = contentType == null ? null : ALLOWED_TYPES.get(contentType);
        if (extension == null) {
            throw new InvalidAttachmentException("Formato no soportado: usa JPG, PNG, PDF de una página o audio M4A");
        }
        return extension;
    }

    /**
     * El Content-Type lo declara el cliente y se puede falsificar: se comparan los primeros bytes
     * (firma o "magic number") con el formato declarado.
     */
    private void requireMatchingSignature(String contentType, byte[] content) {
        boolean matches = switch (contentType) {
            case "image/jpeg" -> startsWith(content, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(content, 0x89, 0x50, 0x4E, 0x47);
            case "application/pdf" -> startsWith(content, 0x25, 0x50, 0x44, 0x46);
            // En MP4 la marca "ftyp" va después de los 4 bytes que indican el tamaño de la caja
            case "audio/mp4", "audio/x-m4a" -> hasBytesAt(content, 4, 0x66, 0x74, 0x79, 0x70);
            case "audio/webm" -> startsWith(content, 0x1A, 0x45, 0xDF, 0xA3);
            default -> false;
        };
        if (!matches) {
            throw new InvalidAttachmentException("El contenido del archivo no corresponde al formato " + contentType);
        }
    }

    private static boolean startsWith(byte[] content, int... signature) {
        return hasBytesAt(content, 0, signature);
    }

    private static boolean hasBytesAt(byte[] content, int offset, int... signature) {
        if (content.length < offset + signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((content[offset + i] & 0xFF) != signature[i]) return false;
        }
        return true;
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
