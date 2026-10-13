package com.payverse.user.controller;

import com.payverse.common.error.PayverseException;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.user.dto.KycStatusResponse;
import com.payverse.user.dto.MinKycRequest;
import com.payverse.user.dto.ProfileResponse;
import com.payverse.user.exception.UserErrorCode;
import com.payverse.user.mapper.UserMapper;
import com.payverse.user.service.KycService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/users/me/kyc")
@Tag(name = "KYC", description = "Tier upgrades verified by the MOCK KYC provider")
public class KycController {

    private final KycService kyc;
    private final UserMapper mapper;

    public KycController(KycService kyc, UserMapper mapper) {
        this.kyc = kyc;
        this.mapper = mapper;
    }

    @Operation(summary = "KYC tier and submission history")
    @GetMapping
    public KycStatusResponse status(AuthenticatedUser user) {
        return mapper.toResponse(kyc.overview(user.userId()));
    }

    @Operation(summary = "Upgrade NONE → MIN_KYC with PAN / voter ID / driving licence / passport")
    @PostMapping("/min")
    public ProfileResponse minKyc(@Valid @RequestBody MinKycRequest request, AuthenticatedUser user) {
        return mapper.toResponse(kyc.submitMinKyc(user.userId(), new KycService.MinKycCommand(
                request.documentType(), request.documentNumber(), request.fullName(), request.dateOfBirth())));
    }

    @Operation(summary = "Upgrade MIN_KYC → FULL_KYC with Aadhaar + document upload (JPEG/PNG/PDF, max 5 MB)")
    @PostMapping(path = "/full", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileResponse fullKyc(@RequestParam("aadhaarNumber") String aadhaarNumber,
                                   @RequestPart("document") MultipartFile document,
                                   AuthenticatedUser user) {
        try {
            return mapper.toResponse(kyc.submitFullKyc(user.userId(),
                    new KycService.FullKycCommand(aadhaarNumber, document.getBytes(), document.getContentType())));
        } catch (IOException e) {
            throw new PayverseException(UserErrorCode.USER_KYC_UNSUPPORTED_FILE, "Could not read the upload");
        }
    }
}
