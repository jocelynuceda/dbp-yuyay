package com.yuyay.care.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CareSubjectUpdateRequest(
        @NotBlank @Size(max = 120) String name,
        @Past LocalDate birthDate,
        String notes
) {}