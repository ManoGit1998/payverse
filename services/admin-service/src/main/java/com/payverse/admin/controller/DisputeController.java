package com.payverse.admin.controller;

import com.payverse.admin.dto.DisputeResponse;
import com.payverse.admin.dto.OpenDisputeRequest;
import com.payverse.admin.service.DisputeService;
import com.payverse.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Users raise disputes on their own completed transfers. */
@RestController
@RequestMapping("/api/v1/disputes")
@Tag(name = "Disputes")
public class DisputeController {

    private final DisputeService disputes;

    public DisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    @Operation(summary = "Raise a dispute on a SUCCESS transfer you sent or received")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeResponse open(@Valid @RequestBody OpenDisputeRequest request, AuthenticatedUser user) {
        return DisputeResponse.of(disputes.open(user.userId(), request.transactionId(), request.reason()));
    }

    @Operation(summary = "My disputes")
    @GetMapping
    public List<DisputeResponse> mine(AuthenticatedUser user) {
        return disputes.mine(user.userId()).stream().map(DisputeResponse::of).toList();
    }
}
