package com.payverse.user.controller;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.user.dto.ProfileResponse;
import com.payverse.user.dto.UpdateProfileRequest;
import com.payverse.user.mapper.UserMapper;
import com.payverse.user.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Profile")
public class ProfileController {

    private final ProfileService profiles;
    private final UserMapper mapper;

    public ProfileController(ProfileService profiles, UserMapper mapper) {
        this.profiles = profiles;
        this.mapper = mapper;
    }

    @Operation(summary = "Get my profile")
    @GetMapping
    public ProfileResponse me(AuthenticatedUser user) {
        return mapper.toResponse(profiles.getOrCreate(user.userId()));
    }

    @Operation(summary = "Update my profile (name and date of birth are locked after KYC)")
    @PutMapping
    public ProfileResponse update(@Valid @RequestBody UpdateProfileRequest request, AuthenticatedUser user) {
        return mapper.toResponse(profiles.update(user.userId(),
                new ProfileService.UpdateProfile(request.fullName(), request.email(), request.dateOfBirth())));
    }
}
