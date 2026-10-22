package com.azizx25.smartfinancialwellness.profile.api;

import com.azizx25.smartfinancialwellness.profile.ProfileService;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    ProfileDtos.ProfileResponse get(@AuthenticationPrincipal CurrentUserPrincipal principal) {
        return profileService.get(principal.userId());
    }

    @PutMapping
    ProfileDtos.ProfileResponse update(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @Valid @RequestBody ProfileDtos.UpdateProfileRequest request) {
        return profileService.update(principal.userId(), request);
    }
}
