package com.payverse.bill.controller;

import com.payverse.bill.dto.CreateMandateRequest;
import com.payverse.bill.dto.MandateExecutionResponse;
import com.payverse.bill.dto.MandateResponse;
import com.payverse.bill.mapper.BillMapper;
import com.payverse.bill.service.MandateService;
import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mandates")
@Tag(name = "Auto-pay mandates")
public class MandateController {

    private final MandateService mandates;
    private final BillMapper mapper;

    public MandateController(MandateService mandates, BillMapper mapper) {
        this.mandates = mandates;
        this.mapper = mapper;
    }

    @Operation(summary = "Set up auto-pay (X-Txn-Pin = consent)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MandateResponse create(@RequestHeader(value = "X-Txn-Pin", required = false) String pin,
                                  @Valid @RequestBody CreateMandateRequest r, AuthenticatedUser user) {
        return mapper.toResponse(mandates.create(new MandateService.CreateMandate(user.userId(), r.billerCode(), r.accountRef(), r.frequency(),
                Money.inr(r.maxAmount()), r.amount() == null ? null : Money.inr(r.amount()), r.firstRunAt(), pin)));
    }

    @Operation(summary = "My mandates")
    @GetMapping
    public List<MandateResponse> list(AuthenticatedUser user) {
        return mandates.list(user.userId()).stream().map(mapper::toResponse).toList();
    }

    @Operation(summary = "Cancel a mandate")
    @DeleteMapping("/{id}")
    public MandateResponse cancel(@PathVariable UUID id, AuthenticatedUser user) {
        return mapper.toResponse(mandates.cancel(user.userId(), id));
    }

    @Operation(summary = "Runs of a mandate")
    @GetMapping("/{id}/executions")
    public List<MandateExecutionResponse> executions(@PathVariable UUID id, AuthenticatedUser user) {
        return mandates.executions(user.userId(), id).stream().map(mapper::toResponse).toList();
    }
}
