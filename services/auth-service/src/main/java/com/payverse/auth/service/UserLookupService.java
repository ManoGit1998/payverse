package com.payverse.auth.service;

import com.payverse.auth.entity.User;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.repository.UserRepository;
import com.payverse.common.web.crypto.BlindIndexer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Resolves a mobile number to an active user (used by transaction-service for P2P by mobile). */
@Service
public class UserLookupService {

    private final UserRepository users;
    private final BlindIndexer blindIndexer;

    public UserLookupService(UserRepository users, BlindIndexer blindIndexer) {
        this.users = users;
        this.blindIndexer = blindIndexer;
    }

    @Transactional(readOnly = true)
    public Optional<User> findActiveByMobile(MobileNumber mobile) {
        return users.findByMobileHash(blindIndexer.index(mobile.value())).filter(User::isActive);
    }
}
