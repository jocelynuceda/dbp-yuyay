package com.yuyay.user.service;

import com.yuyay.exception.InvalidOperationException;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.dto.UpdateUserDTO;
import com.yuyay.user.dto.UserResponseDTO;
import com.yuyay.user.entity.User;
import com.yuyay.user.mapper.UserMapper;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class UserService {
    private static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1900, 1, 1);

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final CurrentUserService currentUser;

    @Transactional(readOnly = true)
    public UserResponseDTO getMe() {
        return userMapper.toResponse(findById(currentUser.getCurrentUserId()));
    }

    @Transactional
    public UserResponseDTO updateMe(UpdateUserDTO dto) {
        User user = findById(currentUser.getCurrentUserId());
        user.setName(dto.name());
        if (dto.gender() != null) user.setGender(dto.gender());
        if (dto.birthDate() != null) {
            if (dto.birthDate().isBefore(MIN_BIRTH_DATE)) {
                throw new InvalidOperationException("Revisa la fecha de nacimiento: no puede ser anterior a 1900");
            }
            user.setBirthDate(dto.birthDate());
        }
        return userMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
    }
}
