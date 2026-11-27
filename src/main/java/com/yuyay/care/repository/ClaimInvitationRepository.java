package com.yuyay.care.repository;

import com.yuyay.care.entity.ClaimInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaimInvitationRepository extends JpaRepository<ClaimInvitation, Long> {

    Optional<ClaimInvitation> findByTokenHash(String tokenHash);

    List<ClaimInvitation> findByCareSubjectIdOrderByIdDesc(Long careSubjectId);

    List<ClaimInvitation> findByCareSubjectIdAndExchangedAtIsNullAndRevokedAtIsNull(Long careSubjectId);
}
