package com.yuyay.user.deletion.dto;

/** Un perfil que sobrevive porque tiene otro responsable activo; esta cuenta solo deja de verlo. */
public record ProfileKept(Long id, String name) {
}
