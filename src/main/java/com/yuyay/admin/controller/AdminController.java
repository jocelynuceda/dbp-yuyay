package com.yuyay.admin.controller;

import com.yuyay.admin.dto.AdminStatsResponse;
import com.yuyay.admin.dto.AdminUserResponse;
import com.yuyay.admin.dto.UpdateUserRoleRequest;
import com.yuyay.admin.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/users")
    public Page<AdminUserResponse> listUsers(@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return adminService.listUsers(pageable);
    }

    @GetMapping("/users/{userId}")
    public AdminUserResponse getUser(@PathVariable Long userId) {
        return adminService.getUser(userId);
    }

    @PatchMapping("/users/{userId}/role")
    public AdminUserResponse updateRole(@PathVariable Long userId, @Valid @RequestBody UpdateUserRoleRequest request) {
        return adminService.updateRole(userId, request);
    }

    @GetMapping("/stats")
    public AdminStatsResponse stats() {
        return adminService.stats();
    }
}
