package com.payverse.user.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.crypto.BlindIndexer;
import com.payverse.user.entity.BankAccount;
import com.payverse.user.exception.UserErrorCode;
import com.payverse.user.repository.BankAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Linked bank accounts. The first account becomes primary; removing the primary promotes the oldest
 * remaining account. (Penny-drop / name-match verification is out of scope for the simulation.)
 */
@Service
public class BankAccountService {

    private final BankAccountRepository accounts;
    private final ProfileService profileService;
    private final UserEventPublisher events;
    private final BlindIndexer blindIndexer;
    private final Clock clock;

    public BankAccountService(BankAccountRepository accounts, ProfileService profileService, UserEventPublisher events,
                              BlindIndexer blindIndexer, Clock clock) {
        this.accounts = accounts;
        this.profileService = profileService;
        this.events = events;
        this.blindIndexer = blindIndexer;
        this.clock = clock;
    }

    public record LinkCommand(String accountNumber, String ifsc, String holderName) {

        @Override
        public String toString() {
            return "LinkCommand[****]";
        }
    }

    @Transactional(readOnly = true)
    public List<BankAccount> list(UUID userId) {
        return accounts.findByUserIdAndRemovedAtIsNullOrderByCreatedAt(userId).stream()
                .sorted(Comparator.comparing(BankAccount::isPrimary).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public BankAccount get(UUID userId, UUID accountId) {
        return accounts.findByIdAndUserIdAndRemovedAtIsNull(accountId, userId)
                .orElseThrow(() -> new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_NOT_FOUND, null));
    }

    @Transactional
    public BankAccount link(UUID userId, LinkCommand command) {
        profileService.getOrCreate(userId);
        List<BankAccount> existing = accounts.findByUserIdAndRemovedAtIsNullOrderByCreatedAt(userId);
        if (existing.size() >= BankAccount.MAX_PER_USER) {
            throw new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_LIMIT, "Up to " + BankAccount.MAX_PER_USER + " accounts");
        }
        BankAccount account = BankAccount.link(userId, command.accountNumber(), command.ifsc(), command.holderName(),
                existing.isEmpty(), blindIndexer::index, clock.instant());
        if (accounts.existsByUserIdAndAccountHashAndRemovedAtIsNull(userId, account.getAccountHash())) {
            throw new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_DUPLICATE, null);
        }
        BankAccount saved = accounts.saveAndFlush(account);
        events.bankAccountLinked(saved);
        return saved;
    }

    @Transactional
    public void remove(UUID userId, UUID accountId) {
        BankAccount account = get(userId, accountId);
        boolean wasPrimary = account.isPrimary();
        account.remove(clock.instant());
        accounts.saveAndFlush(account);
        if (wasPrimary) {
            accounts.findByUserIdAndRemovedAtIsNullOrderByCreatedAt(userId).stream().findFirst()
                    .ifPresent(next -> {
                        next.setPrimary(true);
                        accounts.saveAndFlush(next);
                    });
        }
    }

    /** Clears the old primary first (flushed) so the partial unique index never sees two primaries. */
    @Transactional
    public BankAccount makePrimary(UUID userId, UUID accountId) {
        BankAccount target = get(userId, accountId);
        accounts.findByUserIdAndRemovedAtIsNullOrderByCreatedAt(userId).stream()
                .filter(account -> account.isPrimary() && !account.getId().equals(accountId))
                .forEach(account -> {
                    account.setPrimary(false);
                    accounts.saveAndFlush(account);
                });
        target.setPrimary(true);
        return accounts.saveAndFlush(target);
    }
}
