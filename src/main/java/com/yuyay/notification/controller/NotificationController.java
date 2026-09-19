package com.yuyay.notification.controller;

import com.yuyay.notification.dto.NotificationResponseDTO;
import com.yuyay.notification.dto.UnreadCountDTO;
import com.yuyay.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public List<NotificationResponseDTO> list() {
        return notificationService.list();
    }

    @GetMapping("/unread-count")
    public UnreadCountDTO unreadCount() {
        return notificationService.unreadCount();
    }

    @PatchMapping("/{id}/read")
    public NotificationResponseDTO markAsRead(@PathVariable Long id) {
        return notificationService.markAsRead(id);
    }
}