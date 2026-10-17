package com.payverse.recon.client;

import com.payverse.recon.model.FeedRecord;
import com.payverse.recon.model.IntegrityReport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads the reconciliation feeds of the other services (role SERVICE), page by page, by
 * creation / completion time in [from, to).
 */
@Component
public class FeedClient {

    record Page<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    }

    record Journal(UUID journalId, String type, String referenceType, String referenceId, BigDecimal amount, Instant createdAt) {
    }

    record Charge(UUID orderId, UUID userId, BigDecimal amount, String providerReference, String status, Instant createdAt) {
    }

    record SwitchRecord(String rrn, UUID transactionId, BigDecimal amount, String status, Instant createdAt) {
    }

    record Txn(UUID id, String status, BigDecimal amount, Instant completedAt) {
    }

    record Money(BigDecimal amount, String currency) {
    }

    record Integrity(boolean healthy, List<UUID> walletsOutOfBalance, List<UUID> unbalancedJournals, Money transferSuspense) {
    }

    static final int PAGE_SIZE = 500;

    private final RestClient wallet;
    private final RestClient payment;
    private final RestClient transaction;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public FeedClient(@Qualifier("walletRestClient") RestClient wallet, @Qualifier("paymentRestClient") RestClient payment,
                      @Qualifier("transactionRestClient") RestClient transaction, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.wallet = wallet;
        this.payment = payment;
        this.transaction = transaction;
        this.circuitBreakers = circuitBreakers;
    }

    /** Wallet ADD_MONEY journals; reference = the PG provider reference. */
    public List<FeedRecord> addMoneyJournals(Instant from, Instant to) {
        return all("wallet-service", wallet, "/internal/ledger/journals?type=ADD_MONEY", from, to, new ParameterizedTypeReference<Page<Journal>>() { },
                j -> new FeedRecord(j.referenceId(), j.amount(), j.createdAt()));
    }

    /** Successful PG charges; reference = provider reference. */
    public List<FeedRecord> pgCharges(Instant from, Instant to) {
        return all("payment-service", payment, "/internal/pg/charges?status=SUCCEEDED", from, to, new ParameterizedTypeReference<Page<Charge>>() { },
                c -> new FeedRecord(c.providerReference(), c.amount(), c.createdAt()));
    }

    /** UPI switch settlement records; reference = transaction id. */
    public List<FeedRecord> switchRecords(Instant from, Instant to) {
        return all("payment-service", payment, "/internal/upi/switch-records?x=1", from, to, new ParameterizedTypeReference<Page<SwitchRecord>>() { },
                r -> new FeedRecord(r.transactionId().toString(), r.amount(), r.createdAt()));
    }

    /** Transfers that completed (SUCCESS, or later REVERSED by a refund); reference = transaction id. */
    public List<FeedRecord> completedTransfers(Instant from, Instant to) {
        return all("transaction-service", transaction, "/internal/transactions?status=SUCCESS,REVERSED", from, to,
                new ParameterizedTypeReference<Page<Txn>>() { }, t -> new FeedRecord(t.id().toString(), t.amount(), t.completedAt()));
    }

    public IntegrityReport walletIntegrity() {
        Integrity r = circuitBreakers.create("wallet-service").run(() -> wallet.get().uri("/internal/wallets/integrity").retrieve()
                .body(Integrity.class), ClientFailures::rethrow);
        return new IntegrityReport(r.healthy(), r.walletsOutOfBalance(), r.unbalancedJournals(),
                r.transferSuspense() == null ? BigDecimal.ZERO : r.transferSuspense().amount());
    }

    private <T> List<FeedRecord> all(String service, RestClient client, String path, Instant from, Instant to,
                                     ParameterizedTypeReference<Page<T>> type, Function<T, FeedRecord> mapper) {
        List<FeedRecord> records = new ArrayList<>();
        int page = 0;
        Page<T> result;
        do {
            String uri = path + "&from=" + from + "&to=" + to + "&page=" + page + "&size=" + PAGE_SIZE;
            result = circuitBreakers.create(service).run(() -> client.get().uri(uri).retrieve().body(type), ClientFailures::rethrow);
            result.items().stream().map(mapper).forEach(records::add);
            page++;
        } while (page < result.totalPages());
        return records;
    }
}
