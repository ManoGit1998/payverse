package com.payverse.user.controller;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.user.dto.AddBeneficiaryRequest;
import com.payverse.user.dto.BeneficiaryResponse;
import com.payverse.user.mapper.UserMapper;
import com.payverse.user.service.BeneficiaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/beneficiaries")
@Tag(name = "Beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaries;
    private final UserMapper mapper;

    public BeneficiaryController(BeneficiaryService beneficiaries, UserMapper mapper) {
        this.beneficiaries = beneficiaries;
        this.mapper = mapper;
    }

    @Operation(summary = "List saved beneficiaries")
    @GetMapping
    public List<BeneficiaryResponse> list(AuthenticatedUser user) {
        return mapper.toBeneficiaryResponses(beneficiaries.list(user.userId()));
    }

    @Operation(summary = "Save a beneficiary (mobile, VPA or bank account + IFSC)")
    @PostMapping
    public ResponseEntity<BeneficiaryResponse> add(@Valid @RequestBody AddBeneficiaryRequest request, AuthenticatedUser user) {
        var saved = beneficiaries.add(user.userId(), new BeneficiaryService.AddCommand(
                request.type(), request.value(), request.ifsc(), request.nickname()));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(saved));
    }

    @Operation(summary = "Delete a beneficiary")
    @DeleteMapping("/{beneficiaryId}")
    public ResponseEntity<Void> remove(@PathVariable UUID beneficiaryId, AuthenticatedUser user) {
        beneficiaries.remove(user.userId(), beneficiaryId);
        return ResponseEntity.noContent().build();
    }
}
