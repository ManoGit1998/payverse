package com.payverse.notification.repository;

import com.payverse.notification.entity.Channel;
import com.payverse.notification.entity.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateRepository extends JpaRepository<Template, UUID> {

    List<Template> findByCodeAndActiveTrue(String code);

    Optional<Template> findByCodeAndChannel(String code, Channel channel);

    List<Template> findAllByOrderByCodeAscChannelAsc();
}
