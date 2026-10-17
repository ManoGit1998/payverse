package com.payverse.payment.service;

import com.payverse.payment.entity.PgOrder;
import com.payverse.payment.entity.PgOrderStatus;
import com.payverse.payment.entity.UpiSwitchRecord;
import com.payverse.payment.repository.PgOrderRepository;
import com.payverse.payment.repository.UpiSwitchRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Provider-side records for reconciliation-service, by creation time in [from, to). */
@Service
public class ReconFeedService {

    private final PgOrderRepository orders;
    private final UpiSwitchRecordRepository switchRecords;

    public ReconFeedService(PgOrderRepository orders, UpiSwitchRecordRepository switchRecords) {
        this.orders = orders;
        this.switchRecords = switchRecords;
    }

    @Transactional(readOnly = true)
    public Page<PgOrder> charges(PgOrderStatus status, Instant from, Instant to, int page, int size) {
        return orders.findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(status, from, to,
                PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Page<UpiSwitchRecord> switchRecords(Instant from, Instant to, int page, int size) {
        return switchRecords.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(from, to, PageRequest.of(page, size));
    }
}
