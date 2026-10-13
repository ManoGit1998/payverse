package com.payverse.wallet.mapper;

import com.payverse.common.money.Money;
import com.payverse.wallet.dto.LedgerEntryResponse;
import com.payverse.wallet.dto.LimitsResponse;
import com.payverse.wallet.dto.MoneyDto;
import com.payverse.wallet.dto.WalletResponse;
import com.payverse.wallet.entity.Journal;
import com.payverse.wallet.entity.LedgerEntry;
import com.payverse.wallet.entity.Wallet;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;

/** Entities → API responses (MapStruct). */
@Mapper
public interface WalletMapper {

    @Mapping(target = "walletId", source = "wallet.id")
    @Mapping(target = "balance", source = "wallet.balance")
    @Mapping(target = "limits", source = "limits")
    @Mapping(target = "updatedAt", source = "wallet.updatedAt")
    WalletResponse toResponse(Wallet wallet, LimitsResponse limits);

    @Mapping(target = "entryId", source = "entry.id")
    @Mapping(target = "journalId", source = "entry.journalId")
    @Mapping(target = "type", source = "journal.type")
    @Mapping(target = "side", source = "entry.side")
    @Mapping(target = "amount", source = "entry.amount")
    @Mapping(target = "balanceAfter", source = "entry.balanceAfter")
    @Mapping(target = "referenceType", source = "journal.referenceType")
    @Mapping(target = "referenceId", source = "journal.referenceId")
    @Mapping(target = "description", source = "journal.description")
    @Mapping(target = "createdAt", source = "entry.createdAt")
    LedgerEntryResponse toResponse(LedgerEntry entry, Journal journal);

    default MoneyDto money(BigDecimal amount) {
        return amount == null ? null : MoneyDto.of(Money.inr(amount));
    }
}
