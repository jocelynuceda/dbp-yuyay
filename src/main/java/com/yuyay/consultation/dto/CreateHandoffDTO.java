package com.yuyay.consultation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateHandoffDTO(

        @NotBlank
        String reason,

        String questions,

        @NotEmpty
        List<Long> healthEntryIds

) {}