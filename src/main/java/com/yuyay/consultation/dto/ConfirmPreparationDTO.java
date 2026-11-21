package com.yuyay.consultation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record ConfirmPreparationDTO(@NotNull UUID requestId,
        @NotBlank @Size(max = 64, min = 64) String preparationToken,
        @NotBlank @Size(max = 10000) String reason,
        @Size(max = 10000) String questions,
        @NotNull @Size(max = 500) List<@NotNull @Valid Selection> entries) {
    public record Selection(@NotNull @Positive Long entryId, @NotNull @Positive Long versionId) {}
}
