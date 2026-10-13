package com.payverse.user.api;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.user.application.BankAccountService;
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
@RequestMapping("/api/v1/users/me/bank-accounts")
@Tag(name = "Bank accounts")
class BankAccountController {

    private final BankAccountService accounts;
    private final UserApiMapper mapper;

    BankAccountController(BankAccountService accounts, UserApiMapper mapper) {
        this.accounts = accounts;
        this.mapper = mapper;
    }

    @Operation(summary = "List linked bank accounts (masked)")
    @GetMapping
    List<UserDtos.BankAccountResponse> list(AuthenticatedUser user) {
        return mapper.toBankAccountResponses(accounts.list(user.userId()));
    }

    @Operation(summary = "Link a bank account (max 5; the first one becomes primary)")
    @PostMapping
    ResponseEntity<UserDtos.BankAccountResponse> link(@Valid @RequestBody UserDtos.LinkBankAccountRequest request,
                                                      AuthenticatedUser user) {
        var account = accounts.link(user.userId(),
                new BankAccountService.LinkCommand(request.accountNumber(), request.ifsc(), request.holderName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(account));
    }

    @Operation(summary = "Make an account primary")
    @PostMapping("/{accountId}/primary")
    UserDtos.BankAccountResponse makePrimary(@PathVariable UUID accountId, AuthenticatedUser user) {
        return mapper.toResponse(accounts.makePrimary(user.userId(), accountId));
    }

    @Operation(summary = "Unlink a bank account")
    @DeleteMapping("/{accountId}")
    ResponseEntity<Void> remove(@PathVariable UUID accountId, AuthenticatedUser user) {
        accounts.remove(user.userId(), accountId);
        return ResponseEntity.noContent().build();
    }
}
