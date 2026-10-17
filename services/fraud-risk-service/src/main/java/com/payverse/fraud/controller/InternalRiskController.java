package com.payverse.fraud.controller;

import com.payverse.fraud.dto.AssessRequest;
import com.payverse.fraud.dto.AssessResponse;
import com.payverse.fraud.mapper.FraudMapper;
import com.payverse.fraud.service.RiskEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/risk")
@Tag(name = "Internal: risk")
public class InternalRiskController {

    private final RiskEngine engine;
    private final FraudMapper mapper;

    public InternalRiskController(RiskEngine engine, FraudMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    @Operation(summary = "Score a transaction before money moves (idempotent per transactionId)")
    @PostMapping("/assess")
    public AssessResponse assess(@Valid @RequestBody AssessRequest r) {
        return mapper.toAssessResponse(engine.assess(new RiskEngine.AssessCommand(r.transactionId(), r.type(), r.userId(), r.payeeUserId(),
                r.amount(), r.currency() == null ? "INR" : r.currency(), r.deviceId())));
    }
}
