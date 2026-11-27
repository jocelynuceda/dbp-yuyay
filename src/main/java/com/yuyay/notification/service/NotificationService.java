package com.yuyay.notification.service;

import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.common.Pagination;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.notification.dto.NotificationResponseDTO;
import com.yuyay.notification.dto.UnreadCountDTO;
import com.yuyay.notification.entity.Notification;
import com.yuyay.notification.mapper.NotificationMapper;
import com.yuyay.notification.repository.NotificationRepository;
import com.yuyay.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final CareRelationshipRepository careRelationshipRepository;
    private final NotificationMapper notificationMapper;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public Page<NotificationResponseDTO> list(Boolean unread, Pageable pageable) {
        Long userId = currentUserService.getCurrentUserId();
        pageable = Pagination.stable(pageable, "createdAt");
        Page<Notification> notifications = unread == null
                ? notificationRepository.findByUserId(userId, pageable)
                : unread ? notificationRepository.findByUserIdAndReadAtIsNull(userId, pageable)
                : notificationRepository.findByUserIdAndReadAtIsNotNull(userId, pageable);
        Set<Long> visibleSubjects = findVisibleSubjects(userId, notifications.getContent());
        return notifications.map(notification -> toResponse(notification, visibleSubjects));
    }

    @Transactional(readOnly = true)
    public UnreadCountDTO unreadCount() {
        return new UnreadCountDTO(
                notificationRepository.countByUserIdAndReadAtIsNull(currentUserService.getCurrentUserId()));
    }

    public NotificationResponseDTO markAsRead(Long id) {
        Long userId = currentUserService.getCurrentUserId();
        Notification notification = notificationRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notificación no encontrada"));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
        }
        return toResponse(notification, findVisibleSubjects(userId, List.of(notification)));
    }

    private Set<Long> findVisibleSubjects(Long userId, List<Notification> notifications) {
        Set<Long> subjectIds = notifications.stream()
                .filter(notification -> notification.getType().requiresCareSubjectAccess())
                .map(Notification::getCareSubjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return subjectIds.isEmpty() ? Set.of()
                : careRelationshipRepository.findCareSubjectIdsByUserAndStatus(userId, subjectIds, CareStatus.ACTIVE);
    }

    private NotificationResponseDTO toResponse(Notification notification, Set<Long> visibleSubjects) {
        Long subjectId = notification.getCareSubjectId();
        if (notification.getType().requiresCareSubjectAccess()
                && (subjectId == null || !visibleSubjects.contains(subjectId))) {
            return new NotificationResponseDTO(notification.getId(), notification.getType(),
                    "Notificación no disponible", null, null, notification.getReadAt() != null,
                    notification.getReadAt(), notification.getCreatedAt());
        }
        return notificationMapper.toResponse(notification);
    }
}
