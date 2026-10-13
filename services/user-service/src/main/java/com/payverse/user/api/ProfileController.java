package com.payverse.user.api;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.user.application.ProfileService;
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
class ProfileController {

    private final ProfileService profiles;
    private final UserApiMapper mapper;

    ProfileController(ProfileService profiles, UserApiMapper mapper) {
        this.profiles = profiles;
        this.mapper = mapper;
    }

    @Operation(summary = "Get my profile")
    @GetMapping
    UserDtos.ProfileResponse me(AuthenticatedUser user) {
        return mapper.toResponse(profiles.getOrCreate(user.userId()));
    }

    @Operation(summary = "Update my profile (name and date of birth are locked after KYC)")
    @PutMapping
    UserDtos.ProfileResponse update(@Valid @RequestBody UserDtos.UpdateProfileRequest request, AuthenticatedUser user) {
        return mapper.toResponse(profiles.update(user.userId(),
                new ProfileService.UpdateProfile(request.fullName(), request.email(), request.dateOfBirth())));
    }
}
