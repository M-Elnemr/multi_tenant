package com.platform.core.auth;

import com.platform.shared.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    public record IdentifierRequest(@NotBlank String identifier) {}
    public record LoginRequest(@NotBlank String identifier, @NotBlank String password) {}
    public record ActivateRequest(@NotBlank String identifier, @NotBlank String pin, @NotBlank String newPassword) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}

    @PostMapping("/check-identifier")
    public Map<String, Object> checkIdentifier(@Valid @RequestBody IdentifierRequest r, HttpServletRequest req) {
        return Map.of("next", auth.checkIdentifier(r.identifier(), ClientInfo.ip(req)));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest r, HttpServletRequest req) {
        return auth.login(r.identifier(), r.password(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    /** First-time password creation (or reset) with the PIN given by the clinic/store. */
    @PostMapping("/activate")
    public TokenResponse activate(@Valid @RequestBody ActivateRequest r, HttpServletRequest req) {
        return auth.activate(r.identifier(), r.pin(), r.newPassword(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    @PostMapping("/reset-password")
    public TokenResponse reset(@Valid @RequestBody ActivateRequest r, HttpServletRequest req) {
        return auth.resetPassword(r.identifier(), r.pin(), r.newPassword(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest r, HttpServletRequest req) {
        return auth.refresh(r.refreshToken(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody RefreshRequest r) {
        auth.logout(r.refreshToken());
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public TokenResponse.UserSummary me(Authentication a) {
        return auth.me((UUID) a.getPrincipal());
    }
}
