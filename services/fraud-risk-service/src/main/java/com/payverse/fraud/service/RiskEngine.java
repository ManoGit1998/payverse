package com.payverse.fraud.service;

import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.FraudEvents;
import com.payverse.common.outbox.OutboxPublisher;
import com.payverse.fraud.config.FraudProperties;
import com.payverse.fraud.entity.Decision;
import com.payverse.fraud.entity.Flag;
import com.payverse.fraud.entity.RiskAssessment;
import com.payverse.fraud.entity.RiskRuleConfig;
import com.payverse.fraud.entity.UserRiskProfile;
import com.payverse.fraud.model.RiskContext;
import com.payverse.fraud.repository.FlagRepository;
import com.payverse.fraud.repository.KnownDeviceRepository;
import com.payverse.fraud.repository.RiskAssessmentRepository;
import com.payverse.fraud.repository.RiskRuleConfigRepository;
import com.payverse.fraud.repository.UserRiskProfileRepository;
import com.payverse.fraud.repository.VelocityStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Scores a transaction: every enabled rule that triggers adds its weight (capped at 100), the
 * model may raise the score, and thresholds turn it into ALLOW / REVIEW / BLOCK. REVIEW and BLOCK
 * raise a flag and publish {@code fraud.transaction-flagged}. One assessment per transaction:
 * asking again returns the stored verdict.
 */
@Service
public class RiskEngine {

    public record AssessCommand(UUID transactionId, String type, UUID userId, UUID payeeUserId, BigDecimal amount, String currency,
                                String deviceId) {
    }

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final Map<String, RiskRule> rules;
    private final RiskModel model;
    private final RiskRuleConfigRepository configs;
    private final RiskAssessmentRepository assessments;
    private final FlagRepository flags;
    private final UserRiskProfileRepository profiles;
    private final KnownDeviceRepository devices;
    private final VelocityStore velocity;
    private final OutboxPublisher outbox;
    private final FraudProperties properties;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public RiskEngine(List<RiskRule> ruleList, RiskModel model, RiskRuleConfigRepository configs, RiskAssessmentRepository assessments,
                      FlagRepository flags, UserRiskProfileRepository profiles, KnownDeviceRepository devices, VelocityStore velocity,
                      OutboxPublisher outbox, FraudProperties properties, PlatformTransactionManager transactionManager, MeterRegistry meters,
                      Clock clock) {
        this.rules = ruleList.stream().collect(Collectors.toMap(RiskRule::code, Function.identity()));
        this.model = model;
        this.configs = configs;
        this.assessments = assessments;
        this.flags = flags;
        this.profiles = profiles;
        this.devices = devices;
        this.velocity = velocity;
        this.outbox = outbox;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.meters = meters;
        this.clock = clock;
        // series start at 0 so increase()/rate() count the first decisions too (OTLP push)
        for (Decision d : Decision.values()) {
            meters.counter("payverse.risk.decisions", "decision", d.name());
        }
        rules.keySet().forEach(code -> meters.counter("payverse.risk.rule.hits", "rule", code));
    }

    public RiskAssessment assess(AssessCommand command) {
        Optional<RiskAssessment> existing = assessments.findByTransactionId(command.transactionId());
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        velocity.record(command.userId(), command.transactionId(), command.amount(), now); // idempotent per transaction
        RiskContext context = context(command, now);

        List<String> reasons = new ArrayList<>();
        int score = 0;
        for (RiskRuleConfig config : configs.findAllByOrderByCodeAsc()) {
            RiskRule rule = rules.get(config.getCode());
            if (rule == null || !config.isEnabled()) {
                continue;
            }
            if (rule.triggered(context, config)) {
                reasons.add(config.getCode());
                score += config.getWeight();
            }
        }
        score = Math.min(score, 100);
        OptionalInt modelScore = model.score(context);
        if (modelScore.isPresent() && modelScore.getAsInt() > score) {
            score = Math.min(modelScore.getAsInt(), 100);
            reasons.add("MODEL");
        }
        Decision decision = score >= properties.blockScore() ? Decision.BLOCK : score >= properties.reviewScore() ? Decision.REVIEW : Decision.ALLOW;
        RiskAssessment assessment = RiskAssessment.record(command.transactionId(), command.userId(), command.payeeUserId(), command.type(),
                command.amount(), command.currency(), command.deviceId(), score, decision, reasons, model.version(), now);
        try {
            RiskAssessment saved = tx.execute(status -> save(assessment, now));
            meters.counter("payverse.risk.decisions", "decision", saved.getDecision().name()).increment();
            reasons.forEach(r -> meters.counter("payverse.risk.rule.hits", "rule", r).increment());
            return saved;
        } catch (DataIntegrityViolationException raced) {
            return assessments.findByTransactionId(command.transactionId()).orElseThrow(() -> raced);
        }
    }

    private RiskAssessment save(RiskAssessment assessment, Instant now) {
        RiskAssessment saved = assessments.saveAndFlush(assessment);
        if (saved.getDecision() != Decision.ALLOW) {
            flags.save(Flag.raise(saved, now));
            outbox.publish(EventType.TRANSACTION_FLAGGED, saved.getTransactionId().toString(), new FraudEvents.TransactionFlagged(
                    saved.getId(), saved.getTransactionId(), saved.getUserId(), saved.getScore(), saved.getDecision().name(), saved.reasonList()));
            log.warn("Transaction {} {} with score {}: {}", saved.getTransactionId(), saved.getDecision(), saved.getScore(), saved.getReasons());
        }
        return saved;
    }

    private RiskContext context(AssessCommand c, Instant now) {
        UserRiskProfile profile = profiles.findById(c.userId()).orElseGet(() -> UserRiskProfile.empty(c.userId(), now));
        Optional<Instant> deviceFirstSeen = c.deviceId() == null ? Optional.empty() : devices.firstSeen(c.userId(), c.deviceId());
        return new RiskContext(c.transactionId(), c.userId(), c.payeeUserId(), c.type(), c.amount(), c.deviceId(), now,
                profile.getCompletedCount(), profile.averageAmount(), deviceFirstSeen, Optional.ofNullable(profile.getLastPinLockAt()),
                window -> {
                    VelocityStore.Window w = velocity.window(c.userId(), now, window);
                    return new RiskContext.VelocityWindow(w.count(), w.total());
                });
    }
}
