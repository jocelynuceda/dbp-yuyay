package com.yuyay.user.deletion;

import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.service.CareSubjectService;
import com.yuyay.exception.FieldRejectedException;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.deletion.dto.AccountDeletionPreview;
import com.yuyay.user.deletion.dto.AccountDeletionRequest;
import com.yuyay.user.deletion.dto.ProfileAtRisk;
import com.yuyay.user.deletion.dto.ProfileKept;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.PasswordResetCodeRepository;
import com.yuyay.user.repository.RefreshTokenRepository;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Elimina la cuenta borrando el dato identificante y conservando la fila como lápida: catorce
 * entidades la referencian, entre ellas la cadena de auditoría del consentimiento y de las
 * versiones clínicas, así que borrar la fila se llevaría el historial de otras personas.
 *
 * <p>No se puede deshacer, y por eso la confirmación la verifica el servidor: el cliente devuelve
 * los perfiles que la vista previa dijo que se pierden, y si la lista cambió se rechaza.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    static final String CONFIRMATION_WORD = "ELIMINAR";
    private static final String ERASED_NAME = "Cuenta eliminada";
    /** JPQL no acepta un {@code not in ()} vacío; este identificador no existe en la tabla. */
    private static final Long NO_SUBJECT = -1L;

    private final AccountDeletionRepository queries;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetCodeRepository resetCodes;
    private final CareSubjectService careSubjects;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public AccountDeletionPreview deletionPreview() {
        return buildPreview(currentUserService.getCurrentUserId());
    }

    @Transactional
    public void delete(AccountDeletionRequest request) {
        Long actorId = currentUserService.getCurrentUserId();
        if (!CONFIRMATION_WORD.equals(request.confirmation())) {
            throw new FieldRejectedException("confirmation",
                    "Para eliminar la cuenta escribe exactamente " + CONFIRMATION_WORD);
        }

        User actor = users.findForUpdateById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        if (actor.isDeleted()) {
            throw new InvalidOperationException("Esta cuenta ya fue eliminada");
        }

        AccountDeletionPreview preview = buildPreview(actorId);
        if (!preview.canDelete()) {
            throw new InvalidOperationException(String.join(" ", preview.blockers()));
        }

        Set<Long> accepted = Set.copyOf(request.profileIds() == null ? List.of() : request.profileIds());
        List<Long> toDelete = preview.profilesToDelete().stream().map(ProfileAtRisk::id).toList();
        if (!accepted.equals(Set.copyOf(toDelete))) {
            throw new InvalidOperationException(
                    "La lista de perfiles que se perderían cambió. Revisa qué se va a eliminar y confirma otra vez");
        }

        for (Long subjectId : toDelete) {
            // El registro de accesos del perfil no tiene cascada: sin quitarlo, la clave foránea
            // impide borrar el perfil. La regla de no borrar un perfil con historial de salud vive
            // en CareSubjectService y se respeta tal cual.
            queries.deleteAccessLogsOf(subjectId);
            careSubjects.delete(subjectId);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        queries.revokeActiveRelationshipsOf(actorId, CareStatus.REVOKED, CareStatus.ACTIVE, now);
        refreshTokens.revokeAllByUserId(actorId, now);
        resetCodes.findByUserId(actorId).ifPresent(resetCodes::delete);

        int erased = queries.erasePersonalData(actorId, placeholderEmail(actorId), ERASED_NAME, now);
        if (erased != 1) {
            throw new InvalidOperationException("No se pudo eliminar la cuenta; vuelve a intentarlo");
        }
        log.info("Cuenta eliminada: id={}, perfiles eliminados={}", actorId, toDelete.size());
    }

    /**
     * Libera el correo para que la misma persona pueda volver a registrarse, y apunta a un dominio
     * reservado que no puede existir, de modo que la lápida nunca reciba un mensaje.
     */
    private static String placeholderEmail(Long userId) {
        return "eliminado+" + userId + "@yuyay.invalid";
    }

    private AccountDeletionPreview buildPreview(Long userId) {
        List<CareRelationship> active = queries.activeRelationshipsOf(userId, CareStatus.ACTIVE);
        CareSubject own = queries.ownProfileOf(userId).orElse(null);

        List<Long> accessibleIds = active.stream()
                .map(relationship -> relationship.getCareSubject().getId())
                .distinct()
                .toList();
        Set<Long> withAnotherPrincipal = accessibleIds.isEmpty()
                ? Set.of()
                : Set.copyOf(queries.subjectIdsWithAnotherPrincipal(
                        accessibleIds, userId, CareRole.PRINCIPAL, CareStatus.ACTIVE));

        List<CareSubject> lost = new ArrayList<>();
        List<CareSubject> kept = new ArrayList<>();
        for (CareRelationship relationship : active) {
            CareSubject subject = relationship.getCareSubject();
            if (lost.contains(subject) || kept.contains(subject)) continue;
            boolean isOwn = own != null && own.getId().equals(subject.getId());
            boolean soleResponsible = relationship.getRole() == CareRole.PRINCIPAL
                    && !withAnotherPrincipal.contains(subject.getId());
            if (isOwn || soleResponsible) lost.add(subject);
            else kept.add(subject);
        }
        // Puede ser titular de un perfil sin relación activa sobre él: su dato se va igual.
        if (own != null && lost.stream().noneMatch(subject -> subject.getId().equals(own.getId()))) {
            kept.removeIf(subject -> subject.getId().equals(own.getId()));
            lost.add(own);
        }

        List<Long> lostIds = lost.stream().map(CareSubject::getId).toList();
        Map<Long, Long> entryCounts = countsBySubject(lostIds, queries::countActiveEntriesBySubject);
        Map<Long, Long> documentCounts = countsBySubject(lostIds, queries::countAttachmentsBySubject);
        Set<Long> readingDocuments = lostIds.isEmpty()
                ? Set.of()
                : Set.copyOf(queries.subjectIdsWithPendingOcr(lostIds, OcrStatus.PENDING));
        List<ProfileAtRisk> toDelete = lost.stream()
                .map(subject -> {
                    long count = entryCounts.getOrDefault(subject.getId(), 0L);
                    boolean isOwn = own != null && own.getId().equals(subject.getId());
                    return new ProfileAtRisk(subject.getId(), subject.getName(), count, count > 0, isOwn,
                            documentCounts.getOrDefault(subject.getId(), 0L));
                })
                .toList();

        List<String> blockers = new ArrayList<>();
        for (ProfileAtRisk profile : toDelete) {
            if (profile.hasHistory()) {
                blockers.add("El perfil de " + profile.name() + " tiene historial de salud y no se puede"
                        + " eliminar: entrégalo a su titular o vacíalo antes de eliminar la cuenta.");
            }
            if (readingDocuments.contains(profile.id())) {
                blockers.add("Se está leyendo un documento del perfil de " + profile.name()
                        + ". Espera a que termine e inténtalo otra vez.");
            }
        }

        List<Long> deletedIds = toDelete.isEmpty()
                ? List.of(NO_SUBJECT)
                : toDelete.stream().map(ProfileAtRisk::id).toList();

        return new AccountDeletionPreview(
                toDelete,
                kept.stream().map(subject -> new ProfileKept(subject.getId(), subject.getName())).toList(),
                List.copyOf(blockers),
                queries.countDeclarationsKept(userId, deletedIds),
                CONFIRMATION_WORD,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** Convierte las filas (perfil, cantidad) de una consulta agrupada en un mapa. */
    private static Map<Long, Long> countsBySubject(List<Long> subjectIds, Function<List<Long>, List<Object[]>> query) {
        if (subjectIds.isEmpty()) return Map.of();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : query.apply(subjectIds)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }
}
