package com.yuyay.user.dto;

import com.yuyay.common.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Como el género, la fecha de nacimiento es opcional: si no se envía (o llega null) se conserva la
 * que había.
 */
public record UpdateUserDTO(
        @NotBlank @Size(max = 120) String name,
        Gender gender,
        @Past(message = "La fecha de nacimiento debe ser anterior a hoy") LocalDate birthDate
) {}
