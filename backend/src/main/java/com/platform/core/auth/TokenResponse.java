package com.platform.core.auth;

import java.util.List;
import java.util.UUID;

public record TokenResponse(String accessToken, String refreshToken, long expiresIn, UserSummary user) {
    /** {@code screens} maps each dashboard screen the person can open to READ or WRITE (hidden screens are absent). {@code mustChangePassword} is true for a patient who still has the temporary password the clinic gave them. */
    public record UserSummary(UUID id, String firstName, String lastName, String phone, String email, List<String> roles, List<String> permissions, boolean mustChangePassword, java.util.Map<String, String> screens) {
        public UserSummary(UUID id, String firstName, String lastName, String phone, String email, List<String> roles, List<String> permissions, boolean mustChangePassword) {
            this(id, firstName, lastName, phone, email, roles, permissions, mustChangePassword, java.util.Map.of());
        }
        public UserSummary(UUID id, String firstName, String lastName, String phone, String email, List<String> roles, List<String> permissions) {
            this(id, firstName, lastName, phone, email, roles, permissions, false, java.util.Map.of());
        }
    }
}
