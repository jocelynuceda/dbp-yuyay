package com.yuyay.care.repository;

import com.yuyay.care.entity.ClaimInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ClaimInvitationRepository extends JpaRepository<ClaimInvitation, Long> {

    Optional<ClaimInvitation> findByTokenHash(String tokenHash);

    /**
     * Solo el id del perfil, sin traer la invitación a la sesión: el canje necesita saber qué perfil
     * bloquear antes de leer nada que vaya a comprobar. Si cargara aquí la invitación, el
     * {@code findForUpdateByTokenHash} posterior devolvería esa misma instancia ya en memoria y las
     * comprobaciones se harían sobre un estado anterior al bloqueo.
     */
    @Query("select i.careSubject.id from ClaimInvitation i where i.tokenHash = :tokenHash")
    Optional<Long> findCareSubjectIdByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ClaimInvitation i where i.tokenHash = :tokenHash")
    Optional<ClaimInvitation> findForUpdateByTokenHash(String tokenHash);

    List<ClaimInvitation> findByCareSubjectIdOrderByIdDesc(Long careSubjectId);

    List<ClaimInvitation> findByCareSubjectIdAndExchangedAtIsNullAndRevokedAtIsNull(Long careSubjectId);

    /** Una invitación no es historial clínico: si el perfil vacío se borra, se va con él. */
    @Modifying
    @Query("delete from ClaimInvitation i where i.careSubject.id = :careSubjectId")
    void deleteByCareSubjectId(Long careSubjectId);
}
