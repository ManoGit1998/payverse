package com.payverse.payment.service;

import com.payverse.common.money.Money;
import com.payverse.payment.entity.UpiSwitchRecord;
import com.payverse.payment.repository.UpiSwitchRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Records completed P2P transfers as UPI switch settlements (the "provider side" that
 * reconciliation-service compares with the ledger in Phase 7).
 */
@Service
public class SettlementService {

    private final UpiSwitchRecordRepository records;
    private final UpiSwitch upiSwitch;
    private final VpaService vpaService;
    private final Clock clock;

    public SettlementService(UpiSwitchRecordRepository records, UpiSwitch upiSwitch, VpaService vpaService, Clock clock) {
        this.records = records;
        this.upiSwitch = upiSwitch;
        this.vpaService = vpaService;
        this.clock = clock;
    }

    @Transactional
    public void recordSettlement(UUID transactionId, UUID payerUserId, UUID payeeUserId, Money amount) {
        if (records.findByTransactionId(transactionId).isPresent()) {
            return;
        }
        var payer = vpaService.ensureDefault(payerUserId).getVpaHandle();
        var payee = vpaService.ensureDefault(payeeUserId).getVpaHandle();
        String rrn = upiSwitch.settle(transactionId, payer, payee, amount);
        records.save(UpiSwitchRecord.settled(rrn, transactionId, payer.value(), payee.value(), amount, clock.instant()));
    }
}
