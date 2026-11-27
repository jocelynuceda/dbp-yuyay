package com.yuyay.care.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClaimInvitationCreateRequest(
        @NotBlank(message = "Indica el correo de la persona que recibirá su perfil")
        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 150) String invitedEmail
) {}
