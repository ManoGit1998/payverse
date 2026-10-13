package com.payverse.user.application;

import com.payverse.common.error.ErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.user.application.port.BankAccountRepository;
import com.payverse.user.application.port.BeneficiaryRepository;
import com.payverse.user.application.port.DocumentStorage;
import com.payverse.user.application.port.KycRecordRepository;
import com.payverse.user.application.port.KycVerifier;
import com.payverse.user.application.port.ProfileRepository;
import com.payverse.user.application.port.UserEventPublisher;
import com.payverse.user.domain.BankAccount;
import com.payverse.user.domain.Beneficiary;
import com.payverse.user.domain.IdDocument;
import com.payverse.user.domain.IdDocumentType;
import com.payverse.user.domain.KycRecord;
import com.payverse.user.domain.KycTier;
import com.payverse.user.domain.TestAadhaar;
import com.payverse.user.domain.UserErrorCode;
import com.payverse.user.domain.UserProfile;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserApplicationTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    final Map<UUID, UserProfile> profileStore = new HashMap<>();
    final List<KycRecord> kycStore = new ArrayList<>();
    final Map<UUID, BankAccount> accountStore = new HashMap<>();
    final Map<UUID, Beneficiary> beneficiaryStore = new HashMap<>();
    final List<String> events = new ArrayList<>();

    ProfileRepository profiles = new ProfileRepository() {
        @Override
        public Optional<UserProfile> find(UUID userId) {
            return Optional.ofNullable(profileStore.get(userId));
        }

        @Override
        public UserProfile save(UserProfile p) {
            UserProfile saved = new UserProfile(p.userId(), p.fullName(), p.email(), p.dateOfBirth(), p.mobileMasked(),
                    p.kycTier(), p.createdAt(), p.updatedAt(), p.version() + 1);
            profileStore.put(p.userId(), saved);
            return saved;
        }

        @Override
        public void insertIfAbsent(UserProfile p) {
            profileStore.putIfAbsent(p.userId(), p);
        }
    };

    KycRecordRepository records = new KycRecordRepository() {
        @Override
        public KycRecord save(KycRecord record) {
            kycStore.add(record);
            return record;
        }

        @Override
        public List<KycRecord> findByUser(UUID userId) {
            return kycStore.stream().filter(r -> r.userId().equals(userId)).toList();
        }

        @Override
        public boolean isVerifiedForAnotherUser(IdDocument document, UUID userId) {
            return kycStore.stream().anyMatch(r -> r.document().equals(document) && r.status() == KycRecord.Status.VERIFIED
                    && !r.userId().equals(userId));
        }
    };

    KycVerifier verifier = (document, name, dob) -> document.number().contains("9999")
            ? new KycVerifier.Result.Rejected("mock reject") : new KycVerifier.Result.Verified("ref");

    DocumentStorage storage = (userId, content, type) -> new DocumentStorage.StoredDocument("mock-kyc://x", "sha");

    UserEventPublisher publisher = new UserEventPublisher() {
        @Override
        public void kycUpgraded(UUID userId, KycTier from, KycTier to) {
            events.add("kyc:" + from + "->" + to);
        }

        @Override
        public void bankAccountLinked(BankAccount account) {
            events.add("bank:" + account.maskedNumber());
        }
    };

    BankAccountRepository accountRepository = new BankAccountRepository() {
        @Override
        public List<BankAccount> findActive(UUID userId) {
            return accountStore.values().stream().filter(a -> a.userId().equals(userId)).toList();
        }

        @Override
        public Optional<BankAccount> findActive(UUID userId, UUID accountId) {
            return Optional.ofNullable(accountStore.get(accountId)).filter(a -> a.userId().equals(userId));
        }

        @Override
        public boolean existsActive(UUID userId, String accountNumber) {
            return findActive(userId).stream().anyMatch(a -> a.accountNumber().equals(accountNumber));
        }

        @Override
        public BankAccount save(BankAccount account) {
            accountStore.put(account.id(), account);
            return account;
        }

        @Override
        public void remove(UUID accountId, Instant now) {
            accountStore.remove(accountId);
        }
    };

    BeneficiaryRepository beneficiaryRepository = new BeneficiaryRepository() {
        @Override
        public List<Beneficiary> findActive(UUID userId) {
            return beneficiaryStore.values().stream().filter(b -> b.userId().equals(userId)).toList();
        }

        @Override
        public Optional<Beneficiary> findActive(UUID userId, UUID id) {
            return Optional.ofNullable(beneficiaryStore.get(id)).filter(b -> b.userId().equals(userId));
        }

        @Override
        public boolean existsActive(UUID userId, Beneficiary.Type type, String value) {
            return findActive(userId).stream().anyMatch(b -> b.type() == type && b.value().equals(value));
        }

        @Override
        public Beneficiary save(Beneficiary b) {
            beneficiaryStore.put(b.id(), b);
            return b;
        }

        @Override
        public void remove(UUID id, Instant now) {
            beneficiaryStore.remove(id);
        }
    };

    ProfileService profileService;
    KycService kycService;
    BankAccountService bankAccountService;
    BeneficiaryService beneficiaryService;
    UUID user;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(profiles, CLOCK);
        kycService = new KycService(profileService, profiles, records, verifier, storage, publisher, CLOCK);
        bankAccountService = new BankAccountService(accountRepository, profileService, publisher, CLOCK);
        beneficiaryService = new BeneficiaryService(beneficiaryRepository, profileService, CLOCK);
        user = UUID.randomUUID();
    }

    static void assertError(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    KycService.MinKycCommand pan(String number) {
        return new KycService.MinKycCommand(IdDocumentType.PAN, number, "Asha Verma", LocalDate.of(1995, 5, 20));
    }

    @Nested
    class Profiles {

        @Test
        void registrationEventAndLazyCreationConverge() {
            profileService.getOrCreate(user);
            profileService.onUserRegistered(user, "******3210");
            profileService.onUserRegistered(user, "******3210");
            assertThat(profileStore.get(user).mobileMasked()).isEqualTo("******3210");
        }

        @Test
        void update() {
            UserProfile updated = profileService.update(user, new ProfileService.UpdateProfile("Asha Verma", "a@b.co", null));
            assertThat(updated.fullName()).isEqualTo("Asha Verma");
        }
    }

    @Nested
    class Kyc {

        @Test
        void minThenFullKyc() {
            assertThat(kycService.submitMinKyc(user, pan("ABCDE1234F")).kycTier()).isEqualTo(KycTier.MIN_KYC);
            UserProfile full = kycService.submitFullKyc(user, new KycService.FullKycCommand(TestAadhaar.random(), PNG, "image/png"));
            assertThat(full.kycTier()).isEqualTo(KycTier.FULL_KYC);
            assertThat(events).containsExactly("kyc:NONE->MIN_KYC", "kyc:MIN_KYC->FULL_KYC");
            assertThat(kycService.status(user).history()).hasSize(2);
            assertThat(kycService.status(user).tier()).isEqualTo(KycTier.FULL_KYC);
        }

        @Test
        void fullKycRequiresMinKycFirst() {
            assertError(() -> kycService.submitFullKyc(user, new KycService.FullKycCommand(TestAadhaar.random(), PNG, "image/png")),
                    UserErrorCode.USER_KYC_TIER_TRANSITION);
        }

        @Test
        void aadhaarIsNotAcceptedForMinKyc() {
            assertError(() -> kycService.submitMinKyc(user, new KycService.MinKycCommand(IdDocumentType.AADHAAR, TestAadhaar.random(),
                    "Asha Verma", LocalDate.of(1995, 5, 20))), UserErrorCode.USER_KYC_INVALID_DOCUMENT);
        }

        @Test
        void rejectionIsRecordedAndTierUnchanged() {
            assertError(() -> kycService.submitMinKyc(user, pan("ABCDE9999F")), UserErrorCode.USER_KYC_VERIFICATION_FAILED);
            assertThat(kycStore).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(KycRecord.Status.REJECTED));
            assertThat(profileStore.get(user).kycTier()).isEqualTo(KycTier.NONE);
            assertThat(events).isEmpty();
        }

        @Test
        void oneDocumentOneAccount() {
            kycService.submitMinKyc(user, pan("ABCDE1234F"));
            assertError(() -> kycService.submitMinKyc(UUID.randomUUID(), pan("ABCDE1234F")), UserErrorCode.USER_KYC_DOCUMENT_IN_USE);
        }

        @Test
        void uploadValidation() {
            assertError(() -> KycService.validateUpload(new byte[0], "image/png"), UserErrorCode.USER_KYC_UNSUPPORTED_FILE);
            assertError(() -> KycService.validateUpload(PNG, "image/jpeg"), UserErrorCode.USER_KYC_UNSUPPORTED_FILE);
            assertError(() -> KycService.validateUpload("<html>".getBytes(), "text/html"), UserErrorCode.USER_KYC_UNSUPPORTED_FILE);
            assertError(() -> KycService.validateUpload(new byte[(int) KycService.MAX_DOCUMENT_BYTES + 1], "application/pdf"),
                    UserErrorCode.USER_KYC_DOCUMENT_TOO_LARGE);
            KycService.validateUpload(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}, "image/jpeg");
            KycService.validateUpload("%PDF-1.7".getBytes(), "application/pdf");
        }
    }

    @Nested
    class BankAccounts {

        BankAccount link(String number) {
            return bankAccountService.link(user, new BankAccountService.LinkCommand(number, "HDFC0001234", "Asha Verma"));
        }

        @Test
        void firstAccountIsPrimaryAndRemovalPromotesNext() {
            BankAccount first = link("111111111111");
            BankAccount second = link("222222222222");
            assertThat(first.primary()).isTrue();
            assertThat(second.primary()).isFalse();
            assertThat(bankAccountService.list(user).getFirst().id()).isEqualTo(first.id());

            bankAccountService.remove(user, first.id());
            assertThat(bankAccountService.list(user)).singleElement().satisfies(a -> assertThat(a.primary()).isTrue());
            assertThat(events).containsExactly("bank:XXXXXXXX1111", "bank:XXXXXXXX2222");
        }

        @Test
        void makePrimarySwitches() {
            link("111111111111");
            BankAccount second = link("222222222222");
            bankAccountService.makePrimary(user, second.id());
            assertThat(bankAccountService.list(user).stream().filter(BankAccount::primary).map(BankAccount::id)).containsExactly(second.id());
        }

        @Test
        void limitsAndDuplicates() {
            link("111111111111");
            assertError(() -> link("111111111111"), UserErrorCode.USER_BANK_ACCOUNT_DUPLICATE);
            for (int i = 2; i <= 5; i++) {
                link(String.valueOf(i).repeat(12));
            }
            assertError(() -> link("666666666666"), UserErrorCode.USER_BANK_ACCOUNT_LIMIT);
            assertError(() -> bankAccountService.remove(user, UUID.randomUUID()), UserErrorCode.USER_BANK_ACCOUNT_NOT_FOUND);
        }
    }

    @Nested
    class Beneficiaries {

        @Test
        void addListRemove() {
            Beneficiary b = beneficiaryService.add(user, new BeneficiaryService.AddCommand(Beneficiary.Type.VPA, "ravi@payverse", null, "Ravi"));
            assertThat(beneficiaryService.list(user)).containsExactly(b);
            assertError(() -> beneficiaryService.add(user, new BeneficiaryService.AddCommand(Beneficiary.Type.VPA, "RAVI@payverse", null, null)),
                    UserErrorCode.USER_BENEFICIARY_DUPLICATE);
            beneficiaryService.remove(user, b.id());
            assertThat(beneficiaryService.list(user)).isEmpty();
            assertError(() -> beneficiaryService.remove(user, b.id()), UserErrorCode.USER_BENEFICIARY_NOT_FOUND);
            assertError(() -> beneficiaryService.add(user, new BeneficiaryService.AddCommand(null, "x", null, null)),
                    UserErrorCode.USER_BENEFICIARY_INVALID);
        }
    }
}
