package com.payverse.user.application;

import com.payverse.common.error.PayverseException;
import com.payverse.user.application.port.BankAccountRepository;
import com.payverse.user.application.port.UserEventPublisher;
import com.payverse.user.domain.BankAccount;
import com.payverse.user.domain.UserErrorCode;
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
    private final Clock clock;

    public BankAccountService(BankAccountRepository accounts, ProfileService profileService, UserEventPublisher events, Clock clock) {
        this.accounts = accounts;
        this.profileService = profileService;
        this.events = events;
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
        return accounts.findActive(userId).stream()
                .sorted(Comparator.comparing(BankAccount::primary).reversed().thenComparing(BankAccount::createdAt))
                .toList();
    }

    @Transactional(readOnly = true)
    public BankAccount get(UUID userId, UUID accountId) {
        return accounts.findActive(userId, accountId)
                .orElseThrow(() -> new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_NOT_FOUND, null));
    }

    @Transactional
    public BankAccount link(UUID userId, LinkCommand command) {
        profileService.getOrCreate(userId);
        List<BankAccount> existing = accounts.findActive(userId);
        if (existing.size() >= BankAccount.MAX_PER_USER) {
            throw new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_LIMIT, "Up to " + BankAccount.MAX_PER_USER + " accounts");
        }
        BankAccount account = BankAccount.link(userId, command.accountNumber(), command.ifsc(), command.holderName(),
                existing.isEmpty(), clock.instant());
        if (accounts.existsActive(userId, account.accountNumber())) {
            throw new PayverseException(UserErrorCode.USER_BANK_ACCOUNT_DUPLICATE, null);
        }
        BankAccount saved = accounts.save(account);
        events.bankAccountLinked(saved);
        return saved;
    }

    @Transactional
    public void remove(UUID userId, UUID accountId) {
        BankAccount account = get(userId, accountId);
        accounts.remove(account.id(), clock.instant());
        if (account.primary()) {
            accounts.findActive(userId).stream()
                    .min(Comparator.comparing(BankAccount::createdAt))
                    .ifPresent(next -> accounts.save(next.withPrimary(true)));
        }
    }

    @Transactional
    public BankAccount makePrimary(UUID userId, UUID accountId) {
        BankAccount target = get(userId, accountId);
        for (BankAccount account : accounts.findActive(userId)) {
            if (account.primary() && !account.id().equals(accountId)) {
                accounts.save(account.withPrimary(false));
            }
        }
        return accounts.save(target.withPrimary(true));
    }
}
