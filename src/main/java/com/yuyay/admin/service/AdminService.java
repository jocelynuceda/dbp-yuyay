package com.yuyay.admin.service;

import com.yuyay.admin.dto.AdminStatsResponse;
import com.yuyay.admin.dto.AdminUserResponse;
import com.yuyay.admin.dto.UpdateUserRoleRequest;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.care.repository.DelegationRepository;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.health.repository.HealthEntryRepository;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.Role;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private final UserRepository userRepo;
    private final CareSubjectRepository subjectRepo;
    private final CareRelationshipRepository relationshipRepo;
    private final HealthEntryRepository entryRepo;
    private final DelegationRepository delegationRepo;
    private final CurrentUserService currentUserService;

    public Page<AdminUserResponse> listUsers(Pageable pageable) {
        return userRepo.findAll(pageable).map(this::toResponse);
    }

    public AdminUserResponse getUser(Long userId) {
        return toResponse(loadUser(userId));
    }

    @Transactional
    public AdminUserResponse updateRole(Long userId, UpdateUserRoleRequest request) {
        User user = loadUser(userId);
        if (user.getId().equals(currentUserService.getCurrentUserId()) && request.role() != Role.ADMIN) {
            throw new InvalidOperationException("Un administrador no puede quitarse su propio rol");
        }
        user.setRole(request.role());
        return toResponse(user);
    }

    public AdminStatsResponse stats() {
        return new AdminStatsResponse(
                userRepo.count(),
                subjectRepo.count(),
                relationshipRepo.countByStatus(CareStatus.ACTIVE),
                entryRepo.countByDeletedAtIsNull(),
                delegationRepo.countActive(Instant.now()));
    }

    private User loadUser(Long userId) {
        return userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + userId));
    }

    private AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole(),
                user.getCreatedAt(), relationshipRepo.countByUserIdAndStatus(user.getId(), CareStatus.ACTIVE));
    }
}
