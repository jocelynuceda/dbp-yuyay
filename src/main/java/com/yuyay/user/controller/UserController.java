package com.yuyay.user.controller;

import com.yuyay.user.dto.UpdateUserDTO;
import com.yuyay.user.dto.UserResponseDTO;
import com.yuyay.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping("/me")
    public UserResponseDTO me() {
        return userService.getMe();
    }

    @PatchMapping("/me")
    public UserResponseDTO updateMe(@Valid @RequestBody UpdateUserDTO dto) {
        return userService.updateMe(dto);
    }
}
