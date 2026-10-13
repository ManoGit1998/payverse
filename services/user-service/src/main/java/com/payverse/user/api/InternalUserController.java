package com.payverse.user.api;

import com.payverse.common.error.PayverseException;
import com.payverse.user.application.BankAccountService;
import com.payverse.user.application.port.ProfileRepository;
import com.payverse.user.domain.UserErrorCode;
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
class InternalUserController {

    private final ProfileRepository profiles;
    private final BankAccountService accounts;
    private final UserApiMapper mapper;

    InternalUserController(ProfileRepository profiles, BankAccountService accounts, UserApiMapper mapper) {
        this.profiles = profiles;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    @Operation(summary = "Profile summary incl. KYC tier")
    @GetMapping("/{userId}")
    UserDtos.InternalUserResponse user(@PathVariable UUID userId) {
        return profiles.find(userId).map(mapper::toInternal)
                .orElseThrow(() -> new PayverseException(UserErrorCode.USER_PROFILE_NOT_FOUND, null));
    }

    @Operation(summary = "A user's linked bank account (masked) — used for withdrawals")
    @GetMapping("/{userId}/bank-accounts/{accountId}")
    UserDtos.InternalBankAccountResponse bankAccount(@PathVariable UUID userId, @PathVariable UUID accountId) {
        return mapper.toInternal(accounts.get(userId, accountId));
    }
}
