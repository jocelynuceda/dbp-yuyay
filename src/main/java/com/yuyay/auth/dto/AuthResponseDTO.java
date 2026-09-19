package com.yuyay.auth.dto;

import com.yuyay.user.dto.UserResponseDTO;

public record AuthResponseDTO(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UserResponseDTO user
) {}
