package com.yuyay.health.mapper;

import com.yuyay.config.MapStructConfig;
import com.yuyay.health.dto.HealthEntryVersionResponse;
import com.yuyay.health.dto.UserSummary;
import com.yuyay.health.entity.HealthEntryVersion;
import com.yuyay.user.entity.User;
import org.mapstruct.Mapper;

@Mapper(config = MapStructConfig.class)
public interface HealthEntryVersionMapper {

    HealthEntryVersionResponse toResponse(HealthEntryVersion version);

    UserSummary toSummary(User user);
}