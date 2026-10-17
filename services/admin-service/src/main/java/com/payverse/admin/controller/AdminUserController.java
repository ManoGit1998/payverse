package com.payverse.admin.controller;

import com.payverse.admin.client.BackOfficeClients;
import com.payverse.admin.dto.NoteRequest;
import com.payverse.admin.service.UserAdminService;
import com.payverse.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ADMIN and SUPPORT search users; only ADMIN freezes wallets or rotates signing keys. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin: users and wallets")
@Validated
public class AdminUserController {

    private final UserAdminService users;

    public AdminUserController(UserAdminService users) {
        this.users = users;
    }

    @Operation(summary = "Search users by mobile (exact), or by name (contains) and/or KYC tier")
    @GetMapping("/users")
    public List<UserAdminService.UserView> search(@RequestParam(required = false) String mobile, @RequestParam(required = false) String name,
                                                  @RequestParam(required = false) String kycTier,
                                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return users.search(mobile, name, kycTier, page, size);
    }

    @Operation(summary = "One user: profile, KYC tier and wallet")
    @GetMapping("/users/{userId}")
    public UserAdminService.UserView get(@PathVariable UUID userId) {
        return users.get(userId);
    }

    @Operation(summary = "Freeze a wallet (ADMIN): debits and new credits are refused; reversals still apply")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/wallets/{userId}/freeze")
    public BackOfficeClients.Wallet freeze(@PathVariable UUID userId, @Valid @RequestBody NoteRequest request, AuthenticatedUser admin) {
        return users.freeze(admin.subject(), userId, request.note());
    }

    @Operation(summary = "Unfreeze a wallet (ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/wallets/{userId}/unfreeze")
    public BackOfficeClients.Wallet unfreeze(@PathVariable UUID userId, AuthenticatedUser admin) {
        return users.unfreeze(admin.subject(), userId);
    }

    @Operation(summary = "Rotate the JWT signing key (ADMIN); tokens signed with the old key stay valid until they expire")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/security/signing-keys/rotate")
    public Map<String, String> rotate(AuthenticatedUser admin) {
        return Map.of("kid", users.rotateSigningKey(admin.subject()));
    }
}
