package com.yuyay.notification.controller;

import com.yuyay.notification.dto.NotificationResponseDTO;
import com.yuyay.notification.dto.UnreadCountDTO;
import com.yuyay.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import com.yuyay.common.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public PageResponse<NotificationResponseDTO> list(
            @RequestParam(required = false) Boolean unread,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(notificationService.list(unread, pageable));
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