package com.yuyay.auth.dto;

public record DniLookupResponseDTO(String fullName) {
    @Override
    public String toString() {
        return "DniLookupResponseDTO[reservado]";
    }
}
