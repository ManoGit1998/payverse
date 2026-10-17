package com.payverse.fraud.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.fraud.client.WalletClient;
import com.payverse.fraud.entity.Flag;
import com.payverse.fraud.entity.FlagStatus;
import com.payverse.fraud.exception.FraudErrorCode;
import com.payverse.fraud.repository.FlagRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** Review queue for REVIEW/BLOCK assessments. Confirming fraud can freeze the user's wallet. */
@Service
public class FlagService {

    private final FlagRepository flags;
    private final WalletClient wallet;
    private final AuditLogger audit;
    private final Clock clock;

    public FlagService(FlagRepository flags, WalletClient wallet, AuditLogger audit, Clock clock) {
        this.flags = flags;
        this.wallet = wallet;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Flag> list(FlagStatus status, int page, int size) {
        return status == null ? flags.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                : flags.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(page, size));
    }

    /** The wallet freeze is a remote call: if it fails the flag stays OPEN, and freezing again later is harmless. */
    @Transactional
    public Flag resolve(String actor, UUID flagId, FlagStatus resolution, String note, boolean freezeWallet) {
        Flag flag = flags.findById(flagId).orElseThrow(() -> new PayverseException(FraudErrorCode.FRAUD_FLAG_NOT_FOUND, null));
        if (!flag.isOpen()) {
            throw new PayverseException(FraudErrorCode.FRAUD_FLAG_ALREADY_RESOLVED, null);
        }
        if (resolution == null || resolution == FlagStatus.OPEN || (freezeWallet && resolution != FlagStatus.CONFIRMED_FRAUD)) {
            throw new PayverseException(FraudErrorCode.FRAUD_INVALID_RESOLUTION,
                    "Resolve as CONFIRMED_FRAUD or FALSE_POSITIVE; only CONFIRMED_FRAUD can freeze the wallet");
        }
        if (freezeWallet) {
            wallet.freeze(flag.getUserId(), "Confirmed fraud (flag " + flag.getId() + ")");
        }
        flag.resolve(resolution, actor, note, freezeWallet, clock.instant());
        audit.record(actor, "RISK_FLAG_RESOLVED", "Flag", flag.getId().toString(), Map.of("resolution", resolution.name(),
                "transactionId", flag.getTransactionId().toString(), "walletFrozen", freezeWallet));
        return flag;
    }
}
