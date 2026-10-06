package com.platform.core.auth;

import java.util.List;
import java.util.UUID;

public record TokenResponse(String accessToken, String refreshToken, long expiresIn, UserSummary user) {
    public record UserSummary(UUID id, String firstName, String lastName, String phone, String email, List<String> roles) {}
}
