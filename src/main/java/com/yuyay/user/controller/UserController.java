package com.yuyay.user.controller;

import com.yuyay.user.deletion.AccountDeletionService;
import com.yuyay.user.deletion.dto.AccountDeletionPreview;
import com.yuyay.user.deletion.dto.AccountDeletionRequest;
import com.yuyay.user.dto.UpdateUserDTO;
import com.yuyay.user.dto.UserResponseDTO;
import com.yuyay.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final AccountDeletionService accountDeletion;

    @GetMapping("/me")
    public UserResponseDTO me() {
        return userService.getMe();
    }

    @PatchMapping("/me")
    public UserResponseDTO updateMe(@Valid @RequestBody UpdateUserDTO dto) {
        return userService.updateMe(dto);
    }

    /** Qué se pierde al eliminar la cuenta. No elimina nada. */
    @GetMapping("/me/deletion-preview")
    public AccountDeletionPreview deletionPreview() {
        return accountDeletion.deletionPreview();
    }

    /**
     * Elimina la cuenta. Lleva cuerpo, que en un DELETE es inusual, porque la confirmación la tiene
     * que verificar el servidor: un cliente puede no haber mostrado el aviso de lo que se pierde.
     */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@Valid @RequestBody AccountDeletionRequest req) {
        accountDeletion.delete(req);
    }
}
