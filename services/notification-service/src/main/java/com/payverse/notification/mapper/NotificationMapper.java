package com.payverse.notification.mapper;

import com.payverse.notification.dto.DeadLetterResponse;
import com.payverse.notification.dto.NotificationResponse;
import com.payverse.notification.dto.TemplateResponse;
import com.payverse.notification.entity.DeadLetterEvent;
import com.payverse.notification.entity.Notification;
import com.payverse.notification.entity.Template;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface NotificationMapper {

    NotificationResponse toResponse(Notification notification);

    TemplateResponse toResponse(Template template);

    @Mapping(target = "partition", source = "partitionNo")
    @Mapping(target = "offset", source = "offsetNo")
    DeadLetterResponse toResponse(DeadLetterEvent event);
}
