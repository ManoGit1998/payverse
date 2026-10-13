package com.payverse.user.infrastructure.persistence;

import com.payverse.common.web.crypto.BlindIndexer;
import com.payverse.user.application.port.BeneficiaryRepository;
import com.payverse.user.domain.Beneficiary;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaBeneficiaryRepository implements BeneficiaryRepository {

    private final BeneficiaryJpaRepository jpa;
    private final BlindIndexer blindIndexer;

    JpaBeneficiaryRepository(BeneficiaryJpaRepository jpa, BlindIndexer blindIndexer) {
        this.jpa = jpa;
        this.blindIndexer = blindIndexer;
    }

    @Override
    public List<Beneficiary> findActive(UUID userId) {
        return jpa.findByUserIdAndRemovedAtIsNull(userId).stream().map(JpaBeneficiaryRepository::toDomain).toList();
    }

    @Override
    public Optional<Beneficiary> findActive(UUID userId, UUID beneficiaryId) {
        return jpa.findByIdAndUserIdAndRemovedAtIsNull(beneficiaryId, userId).map(JpaBeneficiaryRepository::toDomain);
    }

    @Override
    public boolean existsActive(UUID userId, Beneficiary.Type type, String value) {
        return jpa.existsByUserIdAndTypeAndValueHashAndRemovedAtIsNull(userId, type.name(), blindIndexer.index(value));
    }

    @Override
    public Beneficiary save(Beneficiary beneficiary) {
        BeneficiaryEntity e = new BeneficiaryEntity();
        e.id = beneficiary.id();
        e.userId = beneficiary.userId();
        e.type = beneficiary.type().name();
        e.value = beneficiary.value();
        e.valueHash = blindIndexer.index(beneficiary.value());
        e.ifsc = beneficiary.ifsc();
        e.nickname = beneficiary.nickname();
        e.createdAt = beneficiary.createdAt();
        jpa.saveAndFlush(e);
        return beneficiary;
    }

    @Override
    public void remove(UUID beneficiaryId, Instant now) {
        jpa.markRemoved(beneficiaryId, now);
    }

    private static Beneficiary toDomain(BeneficiaryEntity e) {
        return new Beneficiary(e.id, e.userId, Beneficiary.Type.valueOf(e.type), e.value, e.ifsc, e.nickname, e.createdAt);
    }
}
