package com.yuyay.care.service;

import com.yuyay.care.dto.SelfProfileStatusResponse;
import com.yuyay.care.dto.SelfProfileStatusResponse.Status;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SelfProfileService {
    static final String INACCESSIBLE_MESSAGE =
            "Ya existe un perfil asociado a ti, pero actualmente no tienes acceso. No es posible crear otro perfil propio.";
    private final CareSubjectRepository subjects;
    private final AuthorizationService authorization;

    public List<Long> ownedIds(Long userId) {
        return subjects.findOwnProfileIds(userId);
    }

    public SelfProfileStatusResponse status(Long userId) {
        List<Long> ids = ownedIds(userId);
        Status status = ids.isEmpty() ? Status.NONE
                : ids.stream().allMatch(id -> authorization.hasAccess(userId, id)) ? Status.ACCESSIBLE : Status.INACCESSIBLE;
        return new SelfProfileStatusResponse(status);
    }

    /** El llamador mantiene bloqueada la fila del usuario durante toda la operación. */
    void requireNoOtherProfile(Long userId, Long allowedSubjectId) {
        List<Long> others = ownedIds(userId).stream().filter(id -> !Objects.equals(id, allowedSubjectId)).toList();
        if (others.isEmpty()) return;
        requireAccessible(userId, others);
        throw new InvalidOperationException("Ya tienes un perfil propio. Abre ese perfil para actualizar tus datos.");
    }

    void requireAccessible(Long userId, List<Long> ids) {
        if (ids.stream().anyMatch(id -> !authorization.hasAccess(userId, id))) {
            throw new InvalidOperationException(INACCESSIBLE_MESSAGE);
        }
    }
}
