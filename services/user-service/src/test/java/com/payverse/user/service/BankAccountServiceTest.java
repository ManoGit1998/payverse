package com.payverse.user.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.crypto.BlindIndexer;
import com.payverse.user.entity.BankAccount;
import com.payverse.user.exception.UserErrorCode;
import com.payverse.user.repository.BankAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankAccountServiceTest {

    final List<BankAccount> store = new ArrayList<>();
    final BankAccountRepository repository = mock(BankAccountRepository.class);
    final UserEventPublisher events = mock(UserEventPublisher.class);
    final UUID user = UUID.randomUUID();
    BankAccountService service;
    long tick;

    @BeforeEach
    void setUp() {
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            BankAccount a = inv.getArgument(0);
            if (!store.contains(a)) {
                store.add(a);
            }
            return a;
        });
        when(repository.findByUserIdAndRemovedAtIsNullOrderByCreatedAt(user)).thenAnswer(inv -> store.stream()
                .filter(a -> a.getRemovedAt() == null).sorted(Comparator.comparing(BankAccount::getCreatedAt)).toList());
        when(repository.findByIdAndUserIdAndRemovedAtIsNull(any(), any())).thenAnswer(inv -> store.stream()
                .filter(a -> a.getId().equals(inv.getArgument(0)) && a.getUserId().equals(inv.getArgument(1)) && a.getRemovedAt() == null)
                .findFirst());
        when(repository.existsByUserIdAndAccountHashAndRemovedAtIsNull(any(), anyString())).thenAnswer(inv -> store.stream()
                .anyMatch(a -> a.getAccountHash().equals(inv.getArgument(1)) && a.getRemovedAt() == null));
        // a clock that advances so createdAt ordering is deterministic
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.parse("2026-10-14T10:00:00Z").plusSeconds(tick++);
            }
        };
        service = new BankAccountService(repository, mock(ProfileService.class), events, new BlindIndexer(new byte[32]), clock);
    }

    BankAccount link(String number) {
        return service.link(user, new BankAccountService.LinkCommand(number, "HDFC0001234", "Asha Verma"));
    }

    @Test
    void firstAccountIsPrimaryAndRemovalPromotesNext() {
        BankAccount first = link("111111111111");
        BankAccount second = link("222222222222");
        assertThat(first.isPrimary()).isTrue();
        assertThat(second.isPrimary()).isFalse();
        assertThat(service.list(user).getFirst()).isSameAs(first);

        service.remove(user, first.getId());
        assertThat(service.list(user)).singleElement().satisfies(a -> assertThat(a.isPrimary()).isTrue());
        verify(events, times(2)).bankAccountLinked(any());
    }

    @Test
    void makePrimarySwitches() {
        BankAccount first = link("111111111111");
        BankAccount second = link("222222222222");
        service.makePrimary(user, second.getId());
        assertThat(first.isPrimary()).isFalse();
        assertThat(second.isPrimary()).isTrue();
    }

    @Test
    void limitsDuplicatesAndOwnership() {
        link("111111111111");
        assertThatThrownBy(() -> link("1111 1111 1111"))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.USER_BANK_ACCOUNT_DUPLICATE));
        for (int i = 2; i <= 5; i++) {
            link(String.valueOf(i).repeat(12));
        }
        assertThatThrownBy(() -> link("666666666666"))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.USER_BANK_ACCOUNT_LIMIT));
        assertThatThrownBy(() -> service.remove(UUID.randomUUID(), store.getFirst().getId()))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.USER_BANK_ACCOUNT_NOT_FOUND));
    }
}
