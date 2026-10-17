package com.payverse.admin.client;

import com.payverse.common.error.PayverseException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** The internal APIs admin-service drives (role SERVICE, token from auth-service client credentials). */
@Component
public class BackOfficeClients {

    public record UserLookup(UUID userId, String mobileMasked) {
    }

    public record UserProfile(UUID userId, String fullName, String kycTier, String mobileMasked) {
    }

    public record Money(String amount, String currency) {
    }

    public record Wallet(UUID walletId, UUID userId, Money balance, String status, String statusReason, String kycTier) {
    }

    public record Transaction(UUID id, String type, String status, UUID payerUserId, UUID payeeUserId, String payeeRef, BigDecimal amount,
                              String currency, Instant createdAt, Instant completedAt, UUID refundId, String refundStatus,
                              String refundFailureCode) {
    }

    /** {@code pending} = transaction-service accepted the refund but has not finished it (call again). */
    public record RefundResult(Transaction transaction, boolean pending) {
    }

    private final RestClient auth;
    private final RestClient user;
    private final RestClient wallet;
    private final RestClient transaction;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public BackOfficeClients(@Qualifier("authRestClient") RestClient auth, @Qualifier("userRestClient") RestClient user,
                             @Qualifier("walletRestClient") RestClient wallet, @Qualifier("transactionRestClient") RestClient transaction,
                             CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.auth = auth;
        this.user = user;
        this.wallet = wallet;
        this.transaction = transaction;
        this.circuitBreakers = circuitBreakers;
    }

    /** Empty when nobody is registered with this mobile. */
    public Optional<UserLookup> lookupByMobile(String mobile) {
        return notFoundAsEmpty(() -> call("auth-service", () -> auth.post().uri("/internal/auth/users/lookup").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("mobile", mobile)).retrieve().body(UserLookup.class)));
    }

    public String rotateSigningKey() {
        Map<?, ?> body = call("auth-service", () -> auth.post().uri("/internal/auth/signing-keys/rotate").retrieve().body(Map.class));
        return String.valueOf(body.get("kid"));
    }

    public Optional<UserProfile> profile(UUID userId) {
        return notFoundAsEmpty(() -> call("user-service", () -> user.get().uri("/internal/users/{id}", userId).retrieve().body(UserProfile.class)));
    }

    public List<UserProfile> searchProfiles(String name, String kycTier, int page, int size) {
        UserProfile[] found = call("user-service", () -> user.get().uri(b -> b.path("/internal/users/search")
                .queryParamIfPresent("name", Optional.ofNullable(name)).queryParamIfPresent("kycTier", Optional.ofNullable(kycTier))
                .queryParam("page", page).queryParam("size", size).build()).retrieve().body(UserProfile[].class));
        return found == null ? List.of() : List.of(found);
    }

    public Optional<Wallet> wallet(UUID userId) {
        return notFoundAsEmpty(() -> call("wallet-service", () -> wallet.get().uri("/internal/wallets/{id}", userId).retrieve().body(Wallet.class)));
    }

    public Wallet freeze(UUID userId, String reason) {
        return call("wallet-service", () -> wallet.post().uri("/internal/wallets/{id}/freeze", userId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", reason)).retrieve().body(Wallet.class));
    }

    public Wallet unfreeze(UUID userId) {
        return call("wallet-service", () -> wallet.post().uri("/internal/wallets/{id}/unfreeze", userId).retrieve().body(Wallet.class));
    }

    public Transaction transaction(UUID id) {
        return call("transaction-service", () -> transaction.get().uri("/internal/transactions/{id}", id).retrieve().body(Transaction.class));
    }

    /** Idempotent per refund id. */
    public RefundResult refund(UUID transactionId, UUID refundId, String reason) {
        ResponseEntity<Transaction> response = call("transaction-service", () -> transaction.post()
                .uri("/internal/transactions/{id}/refund", transactionId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refundId", refundId, "reason", reason)).retrieve().toEntity(Transaction.class));
        return new RefundResult(response.getBody(), response.getStatusCode().value() == 202);
    }

    private <T> T call(String service, Supplier<T> request) {
        return circuitBreakers.create(service).run(request, ClientFailures::rethrow);
    }

    private static <T> Optional<T> notFoundAsEmpty(Supplier<T> request) {
        try {
            return Optional.ofNullable(request.get());
        } catch (PayverseException e) {
            if (e.errorCode().httpStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
