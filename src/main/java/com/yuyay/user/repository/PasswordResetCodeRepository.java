package com.yuyay.user.repository;

import com.yuyay.user.entity.PasswordResetCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Long> {
    /** Bloquea la fila para que dos intentos simultáneos no se salten el límite. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetCode> findByUserId(Long userId);
}
