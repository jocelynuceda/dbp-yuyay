package com.yuyay.care.dto;

import com.yuyay.common.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CareSubjectUpdateRequest(
        @NotBlank @Size(max = 120) String name,
        @Past LocalDate birthDate,
        Gender gender,
        String notes
) {}