package com.yuyay.care.service;

import com.yuyay.care.entity.CareAuthorizationType;
import com.yuyay.care.repository.CareAuthorizationDeclarationRepository;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.attachment.service.AttachmentService;
import com.yuyay.care.dto.CareAuthorizationDeclarationRequest;
import com.yuyay.care.dto.CareAuthorizationPolicyResponse.Option;
import com.yuyay.care.dto.CareSubjectCreateRequest;
import com.yuyay.care.dto.CareSubjectResponse;
import com.yuyay.care.dto.CareSubjectUpdateRequest;
import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.exception.CareSubjectNotFoundException;
import com.yuyay.care.exception.InvalidCareAuthorizationDeclarationException;
import com.yuyay.care.mapper.CareSubjectMapper;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.care.repository.ClaimInvitationRepository;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.health.repository.HealthEntryRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.security.TokenHasher;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class CareSubjectService {

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final CareSubjectRepository subjectRepo;
    private final CareRelationshipRepository relationshipRepo;
    private final UserRepository userRepo;
    private final HealthEntryRepository healthEntryRepo;
    private final ClaimInvitationRepository claimInvitations;
    private final AttachmentService attachments;
    private final CareSubjectMapper mapper;
    private final CurrentUserService currentUserService;
    private final AuthorizationService authorizationService;
    private final CareAuthorizationDeclarationService declarations;
    private final AttachmentRepository attachmentRepo;
    private final CareAuthorizationDeclarationRepository declarationRepo;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    /**
     * Crea el perfil propio al registrar la cuenta, dentro de la misma transacción.
     * Usa el alias, cuya longitud ya cumple el contrato de CareSubject, y solo datos proporcionados.
     * No registra una declaración SELF que la persona no haya confirmado.
     */
    public CareSubject ensureSelfProfile(Long userId) {
        User user = userRepo.findForUpdateById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        CareSubject subject = subjectRepo.findByAccountUserId(userId).orElseGet(() ->
                subjectRepo.save(CareSubject.builder()
                        .name(user.getName())
                        .birthDate(user.getBirthDate())
                        .gender(user.getGender())
                        .accountUser(user)
                        .registrationProfile(true)
                        .build()));
        if (relationshipRepo.findByUserIdAndCareSubjectId(userId, subject.getId()).isEmpty()) {
            relationshipRepo.save(CareRelationship.builder()
                    .user(user)
                    .careSubject(subject)
                    .role(CareRole.PRINCIPAL)
                    .status(CareStatus.ACTIVE)
                    .acceptedAt(Instant.now())
                    .build());
        }
        return subject;
    }

    /** Retira solo el perfil inicial sin uso; conserva la fila y la relación como historial. */
    public void prepareSelfProfileReplacement(User actor, Long destinationId) {
        CareSubject previous = subjectRepo.findByAccountUserId(actor.getId()).orElse(null);
        if (previous == null || previous.getId().equals(destinationId)) return;
        // El bloqueo por sí solo no actualiza una instancia que Hibernate ya tenía en memoria.
        entityManager.refresh(previous, LockModeType.PESSIMISTIC_WRITE);
        boolean used = !previous.isRegistrationProfile() || previous.isRegistrationProfileUsed()
                || !previous.getHealthEntries().isEmpty()
                || !previous.getConsultations().isEmpty()
                || !previous.getHandoffs().isEmpty()
                || !previous.getDelegations().isEmpty()
                || !previous.getAccessLogs().isEmpty()
                || attachmentRepo.existsByCareSubjectId(previous.getId())
                || declarationRepo.existsByCareSubjectId(previous.getId())
                || previous.getCareRelationships().stream().anyMatch(r -> !r.getUser().getId().equals(actor.getId()));
        if (used) {
            throw new InvalidOperationException("Tu perfil propio ya contiene información o actividad. Revisa ambos perfiles antes de recibir otra entrega.");
        }
        CareRelationship relationship = relationshipRepo.findByUserIdAndCareSubjectId(actor.getId(), previous.getId())
                .orElseThrow(() -> new InvalidOperationException("Revisa la relación de tu perfil propio antes de continuar."));
        if (relationship.getRole() != CareRole.PRINCIPAL || relationship.getStatus() != CareStatus.ACTIVE) {
            throw new InvalidOperationException("La relación de tu perfil propio cambió. Revisa el estado antes de continuar.");
        }
        previous.setAccountUser(null);
        previous.setRegistrationProfileUsed(true);
        relationship.setStatus(CareStatus.REVOKED);
        relationship.setRevokedAt(Instant.now());
        // Liberar la unicidad antes de asociar el perfil recibido, siempre en la misma transacción.
        subjectRepo.flush();
    }

    public CareSubjectResponse create(CareSubjectCreateRequest req) {
        if (req.declaration() == null) {
            throw new InvalidCareAuthorizationDeclarationException(
                    "Actualiza Yuyay para registrar la declaración de esta persona");
        }
        String requestId = req.requestId();
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new InvalidCareAuthorizationDeclarationException("Indica un identificador de solicitud válido");
        }
        String payloadHash = creationPayloadHash(req);
        Long meId = currentUserService.getCurrentUserId();
        User me = userRepo.findForUpdateById(meId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + meId));
        CareSubject previous = declarations.replayCreation(meId, requestId, payloadHash).orElse(null);
        if (previous != null) {
            return get(previous.getId());
        }
        Option option = CareAuthorizationPolicy.validate(req.declaration());
        boolean self = option.type() == CareAuthorizationType.SELF;
        if (self && !subjectRepo.findOwnProfileIds(meId).isEmpty()) {
            throw new InvalidOperationException("Ya tienes un perfil propio. Abre ese perfil para actualizar tus datos.");
        }

        CareSubject subject = mapper.toEntity(req);
        if (self) subject.setAccountUser(me);
        subjectRepo.save(subject);

        CareRelationship ownerRelationship = CareRelationship.builder()
                .user(me)
                .careSubject(subject)
                .role(CareRole.PRINCIPAL)
                .status(CareStatus.ACTIVE)
                .acceptedAt(Instant.now())
                .build();
        relationshipRepo.save(ownerRelationship);
        declarations.append(subject, me, option, requestId, payloadHash);

        return mapper.toResponse(subject, CareRole.PRINCIPAL, self);
    }

    @Transactional(readOnly = true)
    public List<CareSubjectResponse> listMine() {
        Long meId = currentUserService.getCurrentUserId();

        // Una sola consulta para todos los roles (evita N+1); la unicidad (user, subject) garantiza una fila por persona
        Map<Long, CareRole> roles = relationshipRepo.findByUserIdAndStatus(meId, CareStatus.ACTIVE).stream()
                .collect(Collectors.toMap(rel -> rel.getCareSubject().getId(), CareRelationship::getRole));

        Set<Long> ownProfiles = Set.copyOf(subjectRepo.findOwnProfileIds(meId));
        return subjectRepo.findAllAccessibleBy(meId).stream()
                .map(cs -> mapper.toResponse(cs, roles.get(cs.getId()), ownProfiles.contains(cs.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CareSubjectResponse get(Long subjectId) {
        Long meId = currentUserService.getCurrentUserId();
        authorizationService.requireAccess(meId, subjectId);

        CareSubject cs = loadSubject(subjectId);

        CareRole role = relationshipRepo
                .findByUserIdAndCareSubjectId(meId, subjectId)
                .map(CareRelationship::getRole)
                .orElse(null);

        return mapper.toResponse(cs, role, subjectRepo.findOwnProfileIds(meId).contains(cs.getId()));
    }

    public CareSubjectResponse update(Long subjectId, CareSubjectUpdateRequest req) {
        Long meId = currentUserService.getCurrentUserId();
        // La entrega escribe titular y fecha en la misma fila: sin el bloqueo, esta edición los
        // reescribiría con los valores que leyó antes de que se confirmara.
        CareSubject cs = authorizationService.requireAccessForUpdate(meId, subjectId);

        cs.setName(req.name());
        cs.setBirthDate(req.birthDate());
        if (req.gender() != null) cs.setGender(req.gender());
        cs.setNotes(req.notes());

        CareRole role = relationshipRepo
                .findByUserIdAndCareSubjectId(meId, subjectId)
                .map(CareRelationship::getRole)
                .orElse(null);

        return mapper.toResponse(cs, role, subjectRepo.findOwnProfileIds(meId).contains(cs.getId()));
    }

    public void delete(Long subjectId) {
        Long meId = currentUserService.getCurrentUserId();
        CareSubject cs = authorizationService.requireRoleForUpdate(meId, subjectId, CareRole.PRINCIPAL);
        if (healthEntryRepo.existsByCareSubjectId(subjectId)) {
            throw new InvalidOperationException("No se puede eliminar una persona con historial de salud");
        }
        // Los documentos y las notas de voz apuntan al perfil sin cascada: se van con él
        attachments.deleteAllOfSubject(subjectId);
        // Las invitaciones de entrega apuntan al perfil: sin quitarlas antes, borrar un perfil vacío
        // que alguna vez tuvo una invitación (aunque esté revocada) choca con la clave foránea.
        claimInvitations.deleteByCareSubjectId(subjectId);
        subjectRepo.delete(cs);
    }

    private String creationPayloadHash(CareSubjectCreateRequest request) {
        CareAuthorizationDeclarationRequest declaration = request.declaration();
        // Mantener el orden y los valores de estos campos al comparar reintentos de altas ya guardadas.
        Object[] fields = {
                request.name(), request.birthDate() == null ? null : request.birthDate().toString(),
                request.gender() == null ? null : request.gender().name(), request.notes(),
                declaration.type() == null ? null : declaration.type().name(),
                declaration.textVersion(), declaration.confirmed()
        };
        return TokenHasher.sha256(objectMapper.writeValueAsString(fields));
    }

    private CareSubject loadSubject(Long id) {
        return subjectRepo.findById(id)
                .orElseThrow(() -> new CareSubjectNotFoundException(id));
    }
}
