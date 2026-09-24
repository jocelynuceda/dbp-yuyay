package com.yuyay.health.mapper;

import com.yuyay.config.MapStructConfig;
import com.yuyay.health.dto.HealthEntryVersionResponse;
import com.yuyay.health.dto.UserSummary;
import com.yuyay.health.entity.HealthEntryVersion;
import com.yuyay.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapStructConfig.class)
public interface HealthEntryVersionMapper {

    @Mapping(target = "sourceAttachmentId", source = "sourceAttachment.id")
    HealthEntryVersionResponse toResponse(HealthEntryVersion version);

    UserSummary toSummary(User user);
}