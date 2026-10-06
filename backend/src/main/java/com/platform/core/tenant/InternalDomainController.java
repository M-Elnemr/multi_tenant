package com.platform.core.tenant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the edge proxy (Caddy on-demand TLS "ask") before it requests a certificate for a host.
 * Never exposed through the public proxy; only verified tenant domains are approved, which stops
 * anyone pointing random DNS names at the server to burn certificate quota.
 */
@RestController
@RequestMapping("/internal/domains")
public class InternalDomainController {
    private final DomainService domains;

    public InternalDomainController(DomainService domains) { this.domains = domains; }

    @GetMapping("/allowed")
    public ResponseEntity<Void> allowed(@RequestParam String domain) {
        return domains.isAllowedForTls(domain) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
