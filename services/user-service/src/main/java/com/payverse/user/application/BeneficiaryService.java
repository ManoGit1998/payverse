package com.payverse.user.application;

import com.payverse.common.error.PayverseException;
import com.payverse.user.application.port.BeneficiaryRepository;
import com.payverse.user.domain.Beneficiary;
import com.payverse.user.domain.UserErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Saved payees (contacts) for quick transfers. */
@Service
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaries;
    private final ProfileService profileService;
    private final Clock clock;

    public BeneficiaryService(BeneficiaryRepository beneficiaries, ProfileService profileService, Clock clock) {
        this.beneficiaries = beneficiaries;
        this.profileService = profileService;
        this.clock = clock;
    }

    public record AddCommand(Beneficiary.Type type, String value, String ifsc, String nickname) {
    }

    @Transactional(readOnly = true)
    public List<Beneficiary> list(UUID userId) {
        return beneficiaries.findActive(userId).stream()
                .sorted(Comparator.comparing(Beneficiary::createdAt).reversed())
                .toList();
    }

    @Transactional
    public Beneficiary add(UUID userId, AddCommand command) {
        if (command.type() == null) {
            throw new PayverseException(UserErrorCode.USER_BENEFICIARY_INVALID, "type is required");
        }
        profileService.getOrCreate(userId);
        if (beneficiaries.findActive(userId).size() >= Beneficiary.MAX_PER_USER) {
            throw new PayverseException(UserErrorCode.USER_BENEFICIARY_LIMIT, "Up to " + Beneficiary.MAX_PER_USER + " beneficiaries");
        }
        Beneficiary beneficiary = Beneficiary.save(userId, command.type(), command.value(), command.ifsc(), command.nickname(), clock.instant());
        if (beneficiaries.existsActive(userId, beneficiary.type(), beneficiary.value())) {
            throw new PayverseException(UserErrorCode.USER_BENEFICIARY_DUPLICATE, null);
        }
        return beneficiaries.save(beneficiary);
    }

    @Transactional
    public void remove(UUID userId, UUID beneficiaryId) {
        Beneficiary beneficiary = beneficiaries.findActive(userId, beneficiaryId)
                .orElseThrow(() -> new PayverseException(UserErrorCode.USER_BENEFICIARY_NOT_FOUND, null));
        beneficiaries.remove(beneficiary.id(), clock.instant());
    }
}
