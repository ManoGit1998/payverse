package com.payverse.wallet;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.payverse.common.money.Money;
import com.payverse.common.security.PayverseJwtValidators;
import com.payverse.common.test.KafkaTestcontainer;
import com.payverse.common.test.PostgresTestcontainer;
import com.payverse.common.test.RedisTestcontainer;
import com.payverse.common.test.TestJwts;
import com.payverse.wallet.entity.KycTier;
import com.payverse.wallet.service.AddMoneyService;
import com.payverse.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Shared setup: Postgres + Redis + Kafka (Testcontainers) and one WireMock server playing
 * auth-service (token endpoint, PIN verification) and user-service (bank accounts).
 */
@SpringBootTest(properties = "payverse.wallet.lock-wait=60s")
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, WalletIntegrationTest.TestTokens.class})
abstract class WalletIntegrationTest {

    static final WireMockServer DOWNSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("payverse.wallet.auth-service-url", DOWNSTREAM::baseUrl);
        registry.add("payverse.wallet.user-service-url", DOWNSTREAM::baseUrl);
        registry.add("payverse.service-client.token-uri", () -> DOWNSTREAM.baseUrl() + "/api/v1/auth/oauth/token");
        registry.add("payverse.service-client.client-secret", () -> "it-secret");
    }

    @TestConfiguration
    static class TestTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            return PayverseJwtValidators.configure((NimbusJwtDecoder) TestJwts.INSTANCE.jwtDecoder(), TestJwts.ISSUER, "payverse");
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WalletService walletService;

    @Autowired
    AddMoneyService addMoneyService;

    @BeforeEach
    void stubTokenEndpoint() {
        DOWNSTREAM.resetAll();
        DOWNSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/api/v1/auth/oauth/token").willReturn(okJson(
                "{\"access_token\":\"" + TestJwts.INSTANCE.token("wallet-service", List.of("SERVICE")) + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    }

    static String bearer(UUID userId) {
        return TestJwts.INSTANCE.bearer(userId);
    }

    static String serviceBearer(String clientId) {
        return "Bearer " + TestJwts.INSTANCE.token(clientId, List.of("SERVICE"));
    }

    /** A wallet at the given tier, funded through the real add-money path. */
    UUID fundedWallet(KycTier tier, String amount) {
        UUID userId = UUID.randomUUID();
        walletService.getOrCreate(userId);
        walletService.changeTier(userId, tier);
        if (Money.inr(amount).isPositive()) {
            addMoneyService.addMoney(userId, Money.inr(amount), "UPI", UUID.randomUUID().toString());
        }
        return userId;
    }

    Money balance(UUID userId) {
        return Money.inr(jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = ?", java.math.BigDecimal.class, userId));
    }
}
