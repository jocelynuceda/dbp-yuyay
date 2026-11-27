package com.yuyay.attachment.service;

import com.yuyay.attachment.dto.AttachmentAccessResponse;
import com.yuyay.attachment.dto.AttachmentContent;
import com.yuyay.attachment.dto.AttachmentResponse;
import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.entity.UploadSource;
import com.yuyay.attachment.exception.AttachmentNotFoundException;
import com.yuyay.attachment.exception.InvalidAttachmentException;
import com.yuyay.attachment.image.ImageSize;
import com.yuyay.attachment.mapper.AttachmentMapper;
import com.yuyay.attachment.ratelimit.OcrRateLimiter;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.attachment.storage.FileStorage;
import com.yuyay.audit.entity.AccessAction;
import com.yuyay.common.ClientHeader;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
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
    private final OcrRateLimiter ocrRateLimiter;

    public AttachmentResponse upload(Long subjectId, MultipartFile file, Long consultationId) {
        return upload(subjectId, file, consultationId, null);
    }

    public AttachmentResponse upload(Long subjectId, MultipartFile file, Long consultationId, String source) {
        return upload(subjectId, file, consultationId, source, null);
    }

    public AttachmentResponse upload(Long subjectId, MultipartFile file, Long consultationId, String source,
                                     String client) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);
        String extension = validate(file);
        byte[] content = readBytes(file);
        requireMatchingSignature(file.getContentType(), content);
        // Diagnostic only: header read, never rejects the upload.
        ImageSize size = ImageSize.read(content, file.getContentType());
        UploadSource parsedSource = UploadSource.fromParam(source);
        String uploadSource = parsedSource == null ? null : parsedSource.name().toLowerCase(Locale.ROOT);
        // Informativo: si falta o es inválido se guarda null, no el marcador "unavailable".
        String sanitizedClient = ClientHeader.sanitize(client);
        String uploadClient = "unavailable".equals(sanitizedClient) ? null : sanitizedClient;

        CareSubject subject = careSubjectRepo.findById(subjectId)
                .orElseThrow(() -> new CareSubjectNotFoundException(subjectId));
        User me = userRepo.findById(meId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        // Todo lo que puede fallar con 4xx se valida ANTES de subir a S3: así no quedan archivos huérfanos.
        Consultation consultation = resolveConsultation(subjectId, consultationId);

        // Los audios no pasan por el OCR. Se marcan DONE y no con un estado nuevo porque la columna tiene
        // un CHECK con los valores actuales y ddl-auto=update no lo modifica en las bases existentes.
        boolean audio = file.getContentType().startsWith("audio/");
        // Va después de las validaciones 4xx (un pedido inválido no gasta turno) y antes de guardar el archivo.
        Instant requestedAt = null;
        if (!audio) {
            requestedAt = ocrRateLimiter.acquire(meId);
            // Se registra antes de guardar el archivo: si el almacenamiento falla, el rollback devuelve el turno.
            releaseTurnIfRolledBack(meId, requestedAt);
        }

        String key = "care-subjects/" + subjectId + "/" + UUID.randomUUID() + extension;
        fileStorage.store(key, content, file.getContentType());
        deleteFromStorageIfRolledBack(key);

        Attachment attachment = attachmentRepo.save(Attachment.builder()
                .careSubject(subject)
                .consultation(consultation)
                .uploadedBy(me)
                .originalFilename(cleanFilename(file.getOriginalFilename()))
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .storageKey(key)
                .ocrStatus(audio ? OcrStatus.DONE : OcrStatus.PENDING)
                .ocrRequestedAt(requestedAt)
                .ocrRequestedById(audio ? null : meId)
                .uploadSource(uploadSource)
                .uploadClient(uploadClient)
                .imageWidth(size == null ? null : size.width())
                .imageHeight(size == null ? null : size.height())
                .build());

        log.info("Adjunto subido adjunto={} contentType={} sizeBytes={} width={} height={} source={} client={}",
                attachment.getId(), attachment.getContentType(), attachment.getSizeBytes(),
                size == null ? "unavailable" : String.valueOf(size.width()),
                size == null ? "unavailable" : String.valueOf(size.height()),
                uploadSource == null ? "unavailable" : uploadSource,
                uploadClient == null ? "unavailable" : uploadClient);

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

    public AttachmentResponse rename(Long subjectId, Long attachmentId, String filename) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccessForUpdate(meId, subjectId);
        Attachment attachment = loadForUpdate(subjectId, attachmentId);
        requireFileManager(meId, subjectId, attachment);
        String name = filename.strip();
        String extension = StringUtils.getFilenameExtension(name);
        String stem = StringUtils.stripFilenameExtension(name);
        if (stem.isBlank() || stem.matches("[. ]+") || name.matches("(?s).*[\\p{Cc}\\p{Cf}/\\\\:*?\"<>|].*")
                || extension == null || !matchesFileType(extension, attachment.getContentType())) {
            throw new InvalidAttachmentException("Usa un nombre válido y conserva la extensión del archivo.");
        }
        if (!name.equals(attachment.getOriginalFilename())) {
            attachment.setOriginalFilename(name);
            eventPublisher.publishEvent(AccessRecordedEvent.byUser(subjectId, meId, AccessAction.UPDATE, "ATTACHMENT", attachmentId));
        }
        return mapper.toResponse(attachment);
    }

    private boolean matchesFileType(String extension, String type) {
        return ("." + extension).equalsIgnoreCase(ALLOWED_TYPES.get(type))
                || ("image/jpeg".equals(type) && "jpeg".equalsIgnoreCase(extension))
                || (("audio/mp4".equals(type) || "audio/x-m4a".equals(type)) && "mp4".equalsIgnoreCase(extension));
    }

    private void requireFileManager(Long meId, Long subjectId, Attachment attachment) {
        if (!attachment.getUploadedBy().getId().equals(meId)) {
            authorizationService.requireRole(meId, subjectId, CareRole.PRINCIPAL);
        }
    }

    /** Lo borra quien lo subió o el principal. Si respalda un registro se conserva: el historial no se reescribe. */
    public void delete(Long subjectId, Long attachmentId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);
        Attachment attachment = load(subjectId, attachmentId);
        requireFileManager(meId, subjectId, attachment);
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

    /**
     * Borra los documentos y las notas de voz de un perfil que se va a eliminar. Quien llama ya
     * comprobó el permiso y que el perfil no tiene historial de salud, así que ningún registro los
     * cita. Los archivos se borran del almacenamiento solo si la transacción se confirma.
     */
    public void deleteAllOfSubject(Long subjectId) {
        List<Attachment> attachments = attachmentRepo.findByCareSubjectIdOrderByUploadedAtDesc(subjectId);
        if (attachments.stream().anyMatch(attachment -> attachment.getOcrStatus() == OcrStatus.PENDING)) {
            throw new InvalidOperationException("Espera a que termine la lectura de los documentos antes de eliminar este perfil");
        }
        attachmentRepo.deleteAll(attachments);
        // Antes de borrar el perfil: la clave foránea del adjunto no tiene cascada
        attachmentRepo.flush();
        attachments.forEach(attachment -> deleteFromStorageAfterCommit(attachment.getStorageKey()));
    }

    public AttachmentResponse retryOcr(Long subjectId, Long attachmentId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);
        Attachment attachment = loadForUpdate(subjectId, attachmentId);
        if (attachment.getOcrStatus() != OcrStatus.FAILED) {
            throw new InvalidOperationException("Solo se puede reintentar un OCR que falló");
        }
        Instant requestedAt = ocrRateLimiter.acquire(meId);
        releaseTurnIfRolledBack(meId, requestedAt);
        attachment.setOcrStatus(OcrStatus.PENDING);
        attachment.setOcrRequestedAt(requestedAt);
        attachment.setOcrRequestedById(meId);
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

    /**
     * El limitador guarda el turno de intervalo en memoria, fuera de la transaccion. Si la transaccion
     * termina en rollback (por ejemplo, fallo el almacenamiento), se devuelve el turno para que el usuario
     * no quede bloqueado por una solicitud que no se proceso. La fila del cupo diario se revierte sola.
     */
    private void releaseTurnIfRolledBack(Long userId, Instant acceptedAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    ocrRateLimiter.release(userId, acceptedAt);
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
