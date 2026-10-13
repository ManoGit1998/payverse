package com.payverse.user.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.crypto.BlindIndexer;
import com.payverse.user.entity.Beneficiary;
import com.payverse.user.entity.BeneficiaryType;
import com.payverse.user.exception.UserErrorCode;
import com.payverse.user.repository.BeneficiaryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Saved payees (contacts) for quick transfers. */
@Service
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaries;
    private final ProfileService profileService;
    private final BlindIndexer blindIndexer;
    private final Clock clock;

    public BeneficiaryService(BeneficiaryRepository beneficiaries, ProfileService profileService, BlindIndexer blindIndexer,
                              Clock clock) {
        this.beneficiaries = beneficiaries;
        this.profileService = profileService;
        this.blindIndexer = blindIndexer;
        this.clock = clock;
    }

    public record AddCommand(BeneficiaryType type, String value, String ifsc, String nickname) {
    }

    @Transactional(readOnly = true)
    public List<Beneficiary> list(UUID userId) {
        return beneficiaries.findByUserIdAndRemovedAtIsNullOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public Beneficiary add(UUID userId, AddCommand command) {
        if (command.type() == null) {
            throw new PayverseException(UserErrorCode.USER_BENEFICIARY_INVALID, "type is required");
        }
        profileService.getOrCreate(userId);
        if (beneficiaries.countByUserIdAndRemovedAtIsNull(userId) >= Beneficiary.MAX_PER_USER) {
            throw new PayverseException(UserErrorCode.USER_BENEFICIARY_LIMIT, "Up to " + Beneficiary.MAX_PER_USER + " beneficiaries");
        }
        Beneficiary beneficiary = Beneficiary.save(userId, command.type(), command.value(), command.ifsc(), command.nickname(),
                blindIndexer::index, clock.instant());
        if (beneficiaries.existsByUserIdAndValueHashAndRemovedAtIsNull(userId, beneficiary.getValueHash())) {
            throw new PayverseException(UserErrorCode.USER_BENEFICIARY_DUPLICATE, null);
        }
        return beneficiaries.saveAndFlush(beneficiary);
    }

    @Transactional
    public void remove(UUID userId, UUID beneficiaryId) {
        Beneficiary beneficiary = beneficiaries.findByIdAndUserIdAndRemovedAtIsNull(beneficiaryId, userId)
                .orElseThrow(() -> new PayverseException(UserErrorCode.USER_BENEFICIARY_NOT_FOUND, null));
        beneficiary.remove(clock.instant());
    }
}
