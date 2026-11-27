package com.yuyay.user.deletion;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.entity.CareSubject;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Las consultas que necesita el borrado de cuenta. Viven aparte de los repositorios de cada
 * entidad para no tocarlos: todas son JPQL explícita, así que el tipo de dominio de la interfaz
 * no limita lo que pueden leer.
 */
public interface AccountDeletionRepository extends Repository<CareRelationship, Long> {

    /** El perfil del que esta cuenta es titular, si lo hay. */
    @Query("select s from CareSubject s where s.accountUser.id = :userId")
    Optional<CareSubject> ownProfileOf(Long userId);

    /** Los perfiles que esta cuenta puede abrir hoy. */
    @Query("""
            select r from CareRelationship r
              join fetch r.careSubject
             where r.user.id = :userId and r.status = :active
            """)
    List<CareRelationship> activeRelationshipsOf(Long userId, CareStatus active);

    /** De esos perfiles, los que seguirían teniendo otro responsable. */
    @Query("""
            select distinct r.careSubject.id from CareRelationship r
             where r.careSubject.id in :subjectIds
               and r.user.id <> :userId
               and r.role = :principal
               and r.status = :active
            """)
    List<Long> subjectIdsWithAnotherPrincipal(
            Collection<Long> subjectIds, Long userId, CareRole principal, CareStatus active);

    /** Cuántos registros de salud vigentes tiene cada perfil, para que la vista previa no mienta. */
    @Query("""
            select h.careSubject.id, count(h) from HealthEntry h
             where h.careSubject.id in :subjectIds and h.deletedAt is null
             group by h.careSubject.id
            """)
    List<Object[]> countActiveEntriesBySubject(Collection<Long> subjectIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CareRelationship r set r.status = :revoked, r.revokedAt = :now
             where r.user.id = :userId and r.status = :active
            """)
    int revokeActiveRelationshipsOf(Long userId, CareStatus revoked, CareStatus active, Instant now);

    /** Las declaraciones que hizo sobre perfiles que sobreviven: no se borran, quedan como lápida. */
    @Query("""
            select count(d) from CareAuthorizationDeclaration d
             where d.declaredBy.id = :userId and d.careSubject.id not in :deletedSubjectIds
            """)
    long countDeclarationsKept(Long userId, Collection<Long> deletedSubjectIds);

    /**
     * El registro de accesos apunta al perfil sin cascada, así que hay que quitarlo antes de
     * borrar el perfil. Se va con la persona: es el detalle de quién vio sus datos.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AccessLog a where a.careSubject.id = :subjectId")
    int deleteAccessLogsOf(Long subjectId);

    /**
     * Borra el dato identificante y deja la lápida. Va en JPQL y no sobre la entidad porque
     * {@code googleSubject} está mapeado como no actualizable: por la entidad el cambio se
     * ignoraría en silencio y la cuenta seguiría entrando con Google.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update User u
               set u.email = :placeholderEmail,
                   u.name = :placeholderName,
                   u.fullName = null,
                   u.passwordHash = null,
                   u.googleSubject = null,
                   u.gender = null,
                   u.birthDate = null,
                   u.deletedAt = :now
             where u.id = :userId and u.deletedAt is null
            """)
    int erasePersonalData(Long userId, String placeholderEmail, String placeholderName, Instant now);
}
