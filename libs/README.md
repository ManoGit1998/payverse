# Shared libraries

Every service depends on these modules. Libraries whose names start with `common-` that contain
Spring code register themselves through Spring Boot auto-configuration
(`META-INF/spring/...AutoConfiguration.imports`), so adding the dependency is all a service has to do.

| Module | Spring? | Usable from entities? | Contents |
|---|---|---|---|
| `common-money` | no | yes | `Money` (BigDecimal, scale 2, HALF_EVEN, INR default), exact `allocate(n)` for bill splits |
| `common-error` | handler only | base package yes (`ErrorCode`, `PayverseException`) | `CommonErrorCode`. `web.ProblemDetailsExceptionHandler` renders RFC 9457 problems with `code`, `correlationId` and `timestamp` |
| `common-events` | no | no | `EventEnvelope<T>`, `EventType` catalog (type, topic, payload class), `Topics`, sealed payload records per domain, `Amount` |
| `common-security` | yes (servlet) | no | Resource-server chain (public probes and docs, `/internal/**` = SERVICE, `/api/v1/admin/**` = ADMIN/SUPPORT), `PayverseJwtValidators` (issuer, audience, `typ=access`), `AuthenticatedUser` controller argument, `CurrentUser`, 401/403 problems |
| `common-web` | yes | no | `CorrelationIdFilter` + RestClient propagation; `IdempotencyService` (Redis claim + Postgres durable record in the caller's transaction); `@Masked` / `PiiMasker`; JSON log PII scrubbing; `FieldEncryptor` (AES-256-GCM, key rotation), `BlindIndexer` (HMAC-SHA256), `EncryptedStringConverter`; shared `logback-spring.xml`; OTel log export |
| `common-outbox` | yes | no | `OutboxPublisher` (must run inside a transaction), `OutboxRelay` (polling, `FOR UPDATE SKIP LOCKED`, ordered, at-least-once), `ProcessedEventGuard` (idempotent consumers), `EventEnvelopeCodec`, Kafka `DefaultErrorHandler` (3 retries, exponential back-off, then `<topic>.dlt`) |
| `common-test` | test | — | `PostgresTestcontainer` (one reusable container, one database per module), `RedisTestcontainer`, `KafkaTestcontainer`, `TestJwts` (RS256 tokens + JWKS), `LayeredArchitectureRules` |

## Shared Flyway migrations

Services set `spring.flyway.locations=classpath:db/migration,classpath:db/common` so they pick up:

- `V0_1__outbox_and_processed_events.sql` (common-outbox)
- `V0_2__idempotency_records.sql` (common-web)

Service migrations start at `V1__...`.

## Using idempotency in a controller

```java
@PostMapping("/transfers")
ResponseEntity<TransferResponse> transfer(@RequestHeader(IdempotencyService.HEADER) String key,
                                          @Valid @RequestBody TransferRequest request,
                                          AuthenticatedUser user) {
    return idempotency.execute("transfer:" + user.userId(), key, request, TransferResponse.class,
            () -> ResponseEntity.status(201).body(transfers.transfer(user.userId(), request)));
}
```

## Publishing and consuming events

```java
@Transactional
void freeze(Wallet wallet) {
    repository.save(wallet.freeze());
    outbox.publish(EventType.WALLET_STATUS_CHANGED, wallet.id().toString(), new WalletStatusChanged(...));
}

@KafkaListener(topics = Topics.TRANSACTION_COMPLETED, groupId = "rewards-service")
void on(String message) {
    var envelope = codec.decode(message, TransactionCompleted.class);
    guard.handleOnce(envelope, "rewards.cashback", e -> cashback.evaluate(e.payload()));
}
```
