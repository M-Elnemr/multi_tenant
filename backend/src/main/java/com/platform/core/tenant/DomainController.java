package com.platform.core.tenant;

import com.platform.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tenant self-service domains: add -> set DNS records -> verify -> make primary. The tenant comes from the Host. */
@RestController
@RequestMapping("/api/v1/tenant/domains")
@PreAuthorize("hasAuthority('domain.manage')")
public class DomainController {
    private final DomainService service;

    public DomainController(DomainService service) { this.service = service; }

    public record AddDomainRequest(@NotBlank String host) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list(TenantContext.require().id()).stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> add(@Valid @RequestBody AddDomainRequest r, Authentication a) {
        return view(service.addCustomDomain(TenantContext.require().id(), (UUID) a.getPrincipal(), r.host()));
    }

    @PostMapping("/{id}/verify")
    public Map<String, Object> verify(@PathVariable UUID id) {
        return view(service.verify(TenantContext.require().id(), id));
    }

    @PostMapping("/{id}/primary")
    public Map<String, Object> primary(@PathVariable UUID id) {
        return view(service.makePrimary(TenantContext.require().id(), id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id, Authentication a) {
        service.remove(TenantContext.require().id(), (UUID) a.getPrincipal(), id);
    }

    private Map<String, Object> view(TenantDomain d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("host", d.getHost());
        m.put("kind", d.getKind());
        m.put("primary", d.isPrimary());
        m.put("verified", d.isVerified());
        m.put("sslStatus", d.getSslStatus());
        m.put("dnsInstructions", service.instructions(d));
        return m;
    }
}
