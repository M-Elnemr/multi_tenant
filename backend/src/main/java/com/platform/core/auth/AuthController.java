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
    private final AccountAuthService accounts;

    private final com.platform.shared.PlatformProperties props;
    private final TotpService totp;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public AuthController(AuthService auth, SsoService sso, AccountAuthService accounts, com.platform.shared.PlatformProperties props, TotpService totp, org.springframework.jdbc.core.simple.JdbcClient jdbc) { this.auth = auth; this.sso = sso; this.accounts = accounts; this.props = props; this.totp = totp; this.jdbc = jdbc; }

    public record IdentifierRequest(@NotBlank String identifier) {}
    /** `app` says which mobile app is signing in ("business" or "client"); web and unknown callers leave it empty. */
    public record LoginRequest(@NotBlank String identifier, @NotBlank String password, String app, String totpCode) {}
    public record CodeRequest(String code) {}
    public record DisableMfaRequest(String code) {}
    public record ActivateRequest(@NotBlank String identifier, @NotBlank String pin, @NotBlank String newPassword) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record HandoffRequest(@jakarta.validation.constraints.NotNull UUID tenantId) {}
    public record RedeemRequest(@NotBlank String ticket) {}
    public record GoogleRequest(@NotBlank String credential) {}

    @PostMapping("/check-identifier")
    public Map<String, Object> checkIdentifier(@Valid @RequestBody IdentifierRequest r, HttpServletRequest req) {
        return Map.of("next", auth.checkIdentifier(r.identifier(), ClientInfo.ip(req)));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest r, HttpServletRequest req) {
        return auth.login(r.identifier(), r.password(), r.app(), r.totpCode(), ClientInfo.ip(req), req.getHeader("User-Agent"));
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

    /** Shop clients: sign in (or sign up) with Google on a store's host. */
    @PostMapping("/client/google")
    public TokenResponse clientGoogle(@Valid @RequestBody GoogleRequest r, HttpServletRequest req) {
        return accounts.clientGoogle(r.credential(), com.platform.shared.TenantContext.get(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    public record ClientRegisterRequest(String name, String phone, String password) {}
    public record ClientVerifyRequest(String phone, String code) {}
    public record ClientLoginRequest(String phone, String password) {}
    public record ClientForgotRequest(String phone) {}
    public record ClientResetRequest(String phone, String code, String newPassword) {}

    /** Shop clients: sign up with a phone and password. A code is sent on WhatsApp; the account exists only after it is entered. */
    @PostMapping("/client/register")
    public Map<String, Object> clientRegister(@RequestBody ClientRegisterRequest r, HttpServletRequest req) {
        accounts.clientRegister(r.name(), r.phone(), r.password(), ClientInfo.ip(req));
        return Map.of("sent", true, "expiresInMinutes", 10);
    }

    @PostMapping("/client/register/verify")
    public TokenResponse clientRegisterVerify(@RequestBody ClientVerifyRequest r, HttpServletRequest req) {
        return accounts.clientRegisterVerify(r.phone(), r.code(), com.platform.shared.TenantContext.get(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    @PostMapping("/client/login")
    public TokenResponse clientLogin(@RequestBody ClientLoginRequest r, HttpServletRequest req) {
        return accounts.clientPhoneLogin(r.phone(), r.password(), com.platform.shared.TenantContext.get(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    @PostMapping("/client/password/forgot")
    public Map<String, Object> clientForgot(@RequestBody ClientForgotRequest r, HttpServletRequest req) {
        accounts.clientForgot(r.phone(), ClientInfo.ip(req));
        return Map.of("sent", true, "expiresInMinutes", 10);
    }

    @PostMapping("/client/password/reset")
    public TokenResponse clientReset(@RequestBody ClientResetRequest r, HttpServletRequest req) {
        return accounts.clientReset(r.phone(), r.code(), r.newPassword(), com.platform.shared.TenantContext.get(), ClientInfo.ip(req), req.getHeader("User-Agent"));
    }

    /** Public: lets the storefront know whether to show the Google button (the client id is public by design). */
    @GetMapping("/client/config")
    public Map<String, Object> clientConfig() {
        String id = accounts.googleClientId();
        // signInHost: the one address registered with Google. Shops send their visitors there to sign in (Google cannot list every shop subdomain).
        boolean codes = accounts.codesAvailable();   // registering with a phone needs the platform's WhatsApp number to send the code
        return id == null ? Map.of("googleEnabled", false, "phoneRegisterEnabled", codes) : Map.of("googleEnabled", true, "googleClientId", id, "signInHost", props.rootDomain(), "phoneRegisterEnabled", codes);
    }

    // ---- two-step sign-in and session control (signed-in staff only) ----------------------------------------------------------------

    @GetMapping("/2fa")
    public Map<String, Object> mfaStatus(Authentication a) { return Map.of("enabled", totp.enabled((UUID) a.getPrincipal())); }

    @PostMapping("/2fa/setup")
    public Map<String, Object> mfaSetup(Authentication a) { return totp.setup((UUID) a.getPrincipal()); }

    @PostMapping("/2fa/enable")
    public Map<String, Object> mfaEnable(@RequestBody CodeRequest r, Authentication a) { return Map.of("recoveryCodes", totp.enable((UUID) a.getPrincipal(), r.code())); }

    @PostMapping("/2fa/disable")
    public Map<String, Object> mfaDisable(@RequestBody DisableMfaRequest r, Authentication a) { totp.disable((UUID) a.getPrincipal(), r.code()); return Map.of("ok", true); }

    /** "Sign out everywhere": every refresh token of this account stops working (access tokens expire within minutes). */
    @PostMapping("/logout-all")
    public Map<String, Object> logoutAll(Authentication a) {
        int n = jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", (UUID) a.getPrincipal()).update();
        return Map.of("ok", true, "sessions", n);
    }

    @GetMapping("/me")
    public TokenResponse.UserSummary me(Authentication a) {
        return auth.me((UUID) a.getPrincipal());
    }
}
