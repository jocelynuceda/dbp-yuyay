package com.yuyay.care.repository;

import com.yuyay.care.entity.CareAuthorizationDeclaration;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface CareAuthorizationDeclarationRepository extends Repository<CareAuthorizationDeclaration, Long> {
    <S extends CareAuthorizationDeclaration> S save(S declaration);

    Optional<CareAuthorizationDeclaration> findByDeclaredByIdAndCreationRequestId(Long userId, String requestId);

    Optional<CareAuthorizationDeclaration> findFirstByCareSubjectIdOrderByIdDesc(Long careSubjectId);

    List<CareAuthorizationDeclaration> findByCareSubjectIdOrderByIdDesc(Long careSubjectId);
}
