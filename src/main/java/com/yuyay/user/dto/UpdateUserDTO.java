package com.yuyay.user.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.yuyay.common.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
public class UpdateUserDTO {
    @Setter
    @NotBlank
    @Size(max = 120)
    private String name;

    @Setter
    private Gender gender;

    @Past(message = "La fecha de nacimiento debe ser anterior a hoy")
    private LocalDate birthDate;
    private boolean birthDateProvided;

    /** Jackson solo llama al setter si el campo está presente, incluido un null explícito. */
    @JsonSetter("birthDate")
    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
        this.birthDateProvided = true;
    }
}
