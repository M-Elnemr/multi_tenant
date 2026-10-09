package com.platform.platform;

import com.platform.commerce.TaxonomyService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** The platform team's tools for the standard category list and owners' suggestions. */
@RestController
@RequestMapping("/api/v1/platform/taxonomy")
public class TaxonomyAdminController {
    private final TaxonomyService taxonomy;

    public TaxonomyAdminController(TaxonomyService taxonomy) { this.taxonomy = taxonomy; }

    public record CreateRequest(Integer parentId, String nameAr, String nameEn) {}
    public record DecideRequest(boolean approve, Integer parentId, String nameAr, String nameEn, String note) {}

    @GetMapping
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public List<Map<String, Object>> list() { return taxonomy.adminList(); }

    @PostMapping
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> create(@RequestBody CreateRequest r, Authentication a) { return taxonomy.adminCreate((UUID) a.getPrincipal(), r.parentId(), r.nameAr(), r.nameEn()); }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> update(@PathVariable int id, @RequestBody Map<String, Object> r, Authentication a) { return taxonomy.adminUpdate((UUID) a.getPrincipal(), id, r); }

    @GetMapping("/requests")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public List<Map<String, Object>> requests(@RequestParam(required = false) String status) { return taxonomy.adminRequests(status); }

    @PostMapping("/requests/{id}/decision")
    @PreAuthorize("hasAuthority('platform.tenant.manage')")
    public Map<String, Object> decide(@PathVariable UUID id, @RequestBody DecideRequest r, Authentication a) { return taxonomy.adminDecide((UUID) a.getPrincipal(), id, r.approve(), r.parentId(), r.nameAr(), r.nameEn(), r.note()); }
}
