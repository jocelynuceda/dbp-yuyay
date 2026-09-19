package com.yuyay.notification.service;

import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.notification.dto.NotificationResponseDTO;
import com.yuyay.notification.dto.UnreadCountDTO;
import com.yuyay.notification.entity.Notification;
import com.yuyay.notification.mapper.NotificationMapper;
import com.yuyay.notification.repository.NotificationRepository;
import com.yuyay.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public List<NotificationResponseDTO> list() {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(currentUserService.getCurrentUserId())
                .stream()
                .map(notificationMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UnreadCountDTO unreadCount() {
        return new UnreadCountDTO(
                notificationRepository.countByUserIdAndReadAtIsNull(currentUserService.getCurrentUserId()));
    }

    public NotificationResponseDTO markAsRead(Long id) {
        Notification notification = notificationRepository
                .findByIdAndUserId(id, currentUserService.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Notificación no encontrada"));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
        }
        return notificationMapper.toResponse(notification);
    }
}