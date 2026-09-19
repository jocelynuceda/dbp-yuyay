package com.yuyay.notification.mapper;

import com.yuyay.config.MapStructConfig;
import com.yuyay.notification.dto.NotificationResponseDTO;
import com.yuyay.notification.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapStructConfig.class)
public interface NotificationMapper {

    @Mapping(target = "read", expression = "java(notification.getReadAt() != null)")
    NotificationResponseDTO toResponse(Notification notification);
}