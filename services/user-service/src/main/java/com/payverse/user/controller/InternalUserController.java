package com.payverse.user.controller;

import com.payverse.user.dto.InternalBankAccountResponse;
import com.payverse.user.dto.InternalUserResponse;
import com.payverse.user.mapper.UserMapper;
import com.payverse.user.service.BankAccountService;
import com.payverse.user.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Service-to-service API (role SERVICE). */
@RestController
@RequestMapping("/internal/users")
@Tag(name = "Internal")
public class InternalUserController {

    private final ProfileService profiles;
    private final BankAccountService accounts;
    private final UserMapper mapper;

    public InternalUserController(ProfileService profiles, BankAccountService accounts, UserMapper mapper) {
        this.profiles = profiles;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    @Operation(summary = "Profile summary incl. KYC tier")
    @GetMapping("/{userId}")
    public InternalUserResponse user(@PathVariable UUID userId) {
        return mapper.toInternal(profiles.get(userId));
    }

    @Operation(summary = "A user's linked bank account (masked) — used for withdrawals")
    @GetMapping("/{userId}/bank-accounts/{accountId}")
    public InternalBankAccountResponse bankAccount(@PathVariable UUID userId, @PathVariable UUID accountId) {
        return mapper.toInternal(accounts.get(userId, accountId));
    }
}
