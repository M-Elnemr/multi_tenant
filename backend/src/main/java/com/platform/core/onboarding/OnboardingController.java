package com.platform.core.onboarding;

import com.platform.core.auth.TokenResponse;
import com.platform.core.tenant.TenantType;
import com.platform.shared.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {
    private final OnboardingService service;

    public OnboardingController(OnboardingService service) { this.service = service; }

    public record CreateTenantRequest(
            @NotNull TenantType type,
            @NotBlank @Size(max = 200) String name,
            @NotBlank String slug,
            @NotBlank @Size(max = 100) String ownerFirstName,
            @Size(max = 100) String ownerLastName,
            @NotBlank String phone,
            String email,
            @NotBlank String password,
            String locale) {}

    /** Live slug check for the signup wizard. */
    @GetMapping("/slug-available")
    public Map<String, Object> slugAvailable(@RequestParam String slug) {
        return Map.of("slug", slug.trim().toLowerCase(), "available", service.slugAvailable(slug));
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateTenantRequest r,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      HttpServletRequest req) {
        var res = service.onboard(new OnboardingService.Command(r.type(), r.name(), r.slug(), r.ownerFirstName(),
                r.ownerLastName(), r.phone(), r.email(), r.password(), r.locale()), key, ClientInfo.ip(req), req.getHeader("User-Agent"));
        Map<String, Object> tenant = new LinkedHashMap<>();
        tenant.put("id", res.tenant().getId());
        tenant.put("slug", res.tenant().getSlug());
        tenant.put("name", res.tenant().getName());
        tenant.put("type", res.tenant().getTenantType());
        tenant.put("status", res.tenant().getStatus());
        tenant.put("trialEndsAt", res.tenant().getTrialEndsAt());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", tenant);
        out.put("host", res.tenant() == null ? null : res.domain() != null ? res.domain().getHost() : null);
        out.put("replay", res.replay());
        TokenResponse t = res.tokens();
        out.put("tokens", t);
        return out;
    }
}
