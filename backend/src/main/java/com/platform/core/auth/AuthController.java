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
    private final SsoService sso;

    public AuthController(AuthService auth, SsoService sso) { this.auth = auth; this.sso = sso; }

    public record IdentifierRequest(@NotBlank String identifier) {}
    public record LoginRequest(@NotBlank String identifier, @NotBlank String password) {}
    public record ActivateRequest(@NotBlank String identifier, @NotBlank String pin, @NotBlank String newPassword) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record HandoffRequest(@jakarta.validation.constraints.NotNull UUID tenantId) {}
    public record RedeemRequest(@NotBlank String ticket) {}

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

    @PostMapping("/change-password")
    public TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest r, Authentication a, HttpServletRequest req) {
        return auth.changePassword((UUID) a.getPrincipal(), r.currentPassword(), r.newPassword(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    /** Platform host: ask for a one-time ticket to open one of your places without signing in again. */
    @PostMapping("/handoff")
    public Map<String, Object> handoff(@Valid @RequestBody HandoffRequest r, Authentication a) {
        if (a == null || !(a.getPrincipal() instanceof UUID userId)) throw com.platform.shared.BusinessException.unauthorized("UNAUTHORIZED", "Sign in first");
        return Map.of("ticket", sso.createTicket(userId, r.tenantId()));
    }

    /** Tenant host: exchange the ticket for a session on this host. Works once, for 60 seconds, only for this host's tenant. */
    @PostMapping("/handoff/redeem")
    public TokenResponse redeem(@Valid @RequestBody RedeemRequest r, HttpServletRequest req) {
        return sso.redeem(r.ticket(), com.platform.shared.TenantContext.require().id(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    @GetMapping("/me")
    public TokenResponse.UserSummary me(Authentication a) {
        return auth.me((UUID) a.getPrincipal());
    }
}
