package com.payverse.auth.application.port;

import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByMobile(MobileNumber mobile);

    User save(User user);
}
