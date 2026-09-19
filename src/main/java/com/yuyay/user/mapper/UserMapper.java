package com.yuyay.user.mapper;

import com.yuyay.config.MapStructConfig;
import com.yuyay.user.dto.UserResponseDTO;
import com.yuyay.user.entity.User;
import org.mapstruct.Mapper;

@Mapper(config = MapStructConfig.class)
public interface UserMapper {
    UserResponseDTO toResponse(User user);
}
