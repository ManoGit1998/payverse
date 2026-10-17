package com.payverse.admin.service;

import com.payverse.admin.client.BackOfficeClients;
import com.payverse.admin.exception.AdminErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** User search and wallet controls. Each write is audited with the acting admin's id. */
@Service
public class UserAdminService {

    public record UserView(UUID userId, String fullName, String mobileMasked, String kycTier, BackOfficeClients.Wallet wallet) {
    }

    private final BackOfficeClients clients;
    private final AuditLogger audit;

    public UserAdminService(BackOfficeClients clients, AuditLogger audit) {
        this.clients = clients;
        this.audit = audit;
    }

    /** By mobile (exact, via auth-service), else by name (contains) and/or KYC tier. */
    public List<UserView> search(String mobile, String name, String kycTier, int page, int size) {
        if (mobile != null && !mobile.isBlank()) {
            return clients.lookupByMobile(mobile.trim()).map(found -> List.of(view(found.userId(), found.mobileMasked()))).orElse(List.of());
        }
        if ((name == null || name.isBlank()) && (kycTier == null || kycTier.isBlank())) {
            throw new PayverseException(AdminErrorCode.ADMIN_INVALID_SEARCH, null);
        }
        return clients.searchProfiles(name, kycTier, page, size).stream()
                .map(p -> new UserView(p.userId(), p.fullName(), p.mobileMasked(), p.kycTier(), clients.wallet(p.userId()).orElse(null)))
                .toList();
    }

    public UserView get(UUID userId) {
        Optional<BackOfficeClients.UserProfile> profile = clients.profile(userId);
        Optional<BackOfficeClients.Wallet> wallet = clients.wallet(userId);
        if (profile.isEmpty() && wallet.isEmpty()) {
            throw new PayverseException(AdminErrorCode.ADMIN_USER_NOT_FOUND, null);
        }
        return new UserView(userId, profile.map(BackOfficeClients.UserProfile::fullName).orElse(null),
                profile.map(BackOfficeClients.UserProfile::mobileMasked).orElse(null),
                profile.map(BackOfficeClients.UserProfile::kycTier).orElse(null), wallet.orElse(null));
    }

    @Transactional
    public BackOfficeClients.Wallet freeze(String actor, UUID userId, String reason) {
        BackOfficeClients.Wallet wallet = clients.freeze(userId, reason);
        audit.record(actor, "WALLET_FROZEN", "User", userId.toString(), Map.of("reason", reason));
        return wallet;
    }

    @Transactional
    public BackOfficeClients.Wallet unfreeze(String actor, UUID userId) {
        BackOfficeClients.Wallet wallet = clients.unfreeze(userId);
        audit.record(actor, "WALLET_UNFROZEN", "User", userId.toString(), Map.of());
        return wallet;
    }

    @Transactional
    public String rotateSigningKey(String actor) {
        String kid = clients.rotateSigningKey();
        audit.record(actor, "SIGNING_KEY_ROTATED", "SigningKey", kid, Map.of());
        return kid;
    }

    private UserView view(UUID userId, String mobileMasked) {
        UserView full = get(userId);
        return new UserView(userId, full.fullName(), mobileMasked, full.kycTier(), full.wallet());
    }
}
