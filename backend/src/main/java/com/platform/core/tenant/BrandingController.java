package com.platform.core.tenant;

import com.platform.audit.AuditService;
import com.platform.files.FileService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import com.platform.shared.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** White-label settings. Branding only overrides design tokens (validated colours, a logo file) - never arbitrary CSS or HTML (spec 28/47). */
@RestController
@RequestMapping("/api/v1/tenant/branding")
public class BrandingController {
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public record BrandingRequest(String primaryColor, String secondaryColor, UUID logoFileId, String locale) {}

    private final JdbcClient jdbc;
    private final FileService files;
    private final AuditService audit;

    public BrandingController(JdbcClient jdbc, FileService files, AuditService audit) {
        this.jdbc = jdbc;
        this.files = files;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settings.manage')")
    public Map<String, Object> get() { return load(TenantContext.require().id()); }

    @PatchMapping
    @PreAuthorize("hasAuthority('settings.manage')")
    @Transactional
    public Map<String, Object> update(@RequestBody BrandingRequest r, Authentication a) {
        UUID tenantId = TenantContext.require().id();
        for (String c : new String[] {r.primaryColor(), r.secondaryColor()})
            if (c != null && !HEX.matcher(c).matches()) throw BusinessException.badRequest("VALIDATION_ERROR", "Colors must be #RRGGBB");
        if (r.locale() != null && !Set.of("ar", "en").contains(r.locale())) throw BusinessException.badRequest("VALIDATION_ERROR", "Unsupported language");
        if (r.logoFileId() != null) files.requireReady(tenantId, r.logoFileId(), Set.of("LOGO"), null);
        jdbc.sql("UPDATE core.branding SET primary_color = coalesce(:p, primary_color), secondary_color = coalesce(:s, secondary_color), logo_file_id = coalesce(:l, logo_file_id), updated_at = now() WHERE tenant_id = :t")
                .param("p", r.primaryColor()).param("s", r.secondaryColor()).param("l", r.logoFileId()).param("t", tenantId).update();
        if (r.locale() != null) jdbc.sql("UPDATE core.tenants SET default_locale = :l, updated_at = now() WHERE id = :t").param("l", r.locale()).param("t", tenantId).update();
        audit.record((UUID) a.getPrincipal(), tenantId, "SETTINGS_CHANGED", "branding", null, null);
        return load(tenantId);
    }

    private Map<String, Object> load(UUID tenantId) {
        Map<String, Object> m = Rows.camel(jdbc.sql("SELECT primary_color, secondary_color, logo_file_id FROM core.branding WHERE tenant_id = :t").param("t", tenantId).query().singleRow());
        m.put("locale", jdbc.sql("SELECT default_locale FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single());
        return m;
    }
}
