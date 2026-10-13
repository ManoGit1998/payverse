package com.payverse.auth.application;

import com.payverse.auth.application.port.UserRepository;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.User;
import com.payverse.auth.domain.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Resolves a mobile number to an active user (used by transaction-service for P2P by mobile). */
@Service
public class UserLookupService {

    private final UserRepository users;

    public UserLookupService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Optional<User> findActiveByMobile(MobileNumber mobile) {
        return users.findByMobile(mobile).filter(user -> user.status() == UserStatus.ACTIVE);
    }
}
