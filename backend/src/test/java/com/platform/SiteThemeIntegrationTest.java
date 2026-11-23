package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Each site (shop, clinic, lawyer) can carry its own validated colour theme; it reaches the public profile; nothing unsafe is stored; tenants never see each other's. */
class SiteThemeIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    private static final String PALETTE = """
            {"siteTheme":{"tokens":{"primary":"#0F766E","secondary":"#134E4A","accent":"#F59E0B","bg":"#FFFFFF","bg2":"#F0FDFA","surface":"#FFFFFF","text":"#0F172A","text2":"#475569","btnBg":"#0F766E","btnText":"#FFFFFF","border":"#CCFBF1"}}}
            """;

    Tenant lawyer() throws Exception {
        String slug = "law" + uniq(), phone = nextPhone();
        String res = mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content("""
                        {"type":"LAWYER","name":"Ahmed Law","slug":"%s","ownerFirstName":"Ahmed","phone":"%s","password":"s3cretPass!","categories":["criminal","family"]}
                        """.formatted(slug, phone)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Tenant(slug, JsonPath.read(res, "$.host"), phone, JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
    }

    /** Runs the same assertions against one site type: unset by default, saved and lower-cased, public, replaceable, clearable, unsafe values rejected. */
    private void exercise(Tenant t, String adminPath, String publicPath, Tenant other, String otherAdminPath, String otherPublicPath, String publicPrefix) throws Exception {
        // an existing site has no theme and renders exactly as before
        onHost(t.host(), t.access(), "GET", adminPath, null).andExpect(status().isOk()).andExpect(jsonPath("$.siteTheme").doesNotExist());

        // anonymous visitors and no token cannot write
        onHost(t.host(), null, "PATCH", adminPath, PALETTE).andExpect(status().is4xxClientError());

        String saved = onHost(t.host(), t.access(), "PATCH", adminPath, PALETTE).andExpect(status().isOk())
                .andExpect(jsonPath("$.siteTheme.tokens.primary").value("#0f766e")).andExpect(jsonPath("$.siteTheme.tokens.btnText").value("#ffffff"))
                .andReturn().getResponse().getContentAsString();
        assertThat((java.util.Map<?, ?>) JsonPath.read(saved, "$.siteTheme.tokens")).hasSize(11);

        // the public profile (what SSR reads) carries it
        onHost(t.host(), null, "GET", publicPath, null).andExpect(status().isOk()).andExpect(jsonPath(publicPrefix + "siteTheme.tokens.accent").value("#f59e0b"));

        // the other tenant is untouched, both in the dashboard and publicly
        onHost(other.host(), other.access(), "GET", otherAdminPath, null).andExpect(jsonPath("$.siteTheme").doesNotExist());
        onHost(other.host(), null, "GET", otherPublicPath, null).andExpect(jsonPath(publicPrefix + "siteTheme").doesNotExist());

        // a partial palette replaces the whole theme (the editor always sends what it wants live)
        onHost(t.host(), t.access(), "PATCH", adminPath, "{\"siteTheme\":{\"tokens\":{\"primary\":\"#112233\"}}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.siteTheme.tokens.primary").value("#112233")).andExpect(jsonPath("$.siteTheme.tokens.accent").doesNotExist());

        // unsafe or invalid values are rejected with a field error and change nothing
        String[] bad = {
                "{\"siteTheme\":{\"tokens\":{\"primary\":\"red\"}}}",
                "{\"siteTheme\":{\"tokens\":{\"primary\":\"#fff\"}}}",
                "{\"siteTheme\":{\"tokens\":{\"primary\":\"#12345g\"}}}",
                "{\"siteTheme\":{\"tokens\":{\"primary\":\"#112233;} body{display:none\"}}}",
                "{\"siteTheme\":{\"tokens\":{\"primary\":\"url(javascript:alert(1))\"}}}",
                "{\"siteTheme\":{\"tokens\":{\"primary\":\"</style><script>alert(1)</script>\"}}}",
                "{\"siteTheme\":{\"tokens\":{\"primary\":123456}}}",
                "{\"siteTheme\":{\"tokens\":{\"evil\":\"#112233\"}}}",
                "{\"siteTheme\":{\"tokens\":{\"x}body{background:url(//e.com)\":\"#112233\"}}}",
                "{\"siteTheme\":{\"tokens\":{\"primary\":\"#112233\"},\"css\":\"body{}\"}}",
                "{\"siteTheme\":\"body{display:none}\"}",
                "{\"siteTheme\":{\"tokens\":[\"#112233\"]}}",
        };
        for (String b : bad) onHost(t.host(), t.access(), "PATCH", adminPath, b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields").exists());
        onHost(t.host(), t.access(), "PATCH", adminPath, "{\"siteTheme\":{\"tokens\":{\"primary\":\"red\"}}}").andExpect(jsonPath("$.fields['siteTheme.tokens.primary']").exists());
        onHost(t.host(), t.access(), "GET", adminPath, null).andExpect(jsonPath("$.siteTheme.tokens.primary").value("#112233"));

        // contrast floor: text on background and button text on button must be at least 3:1
        onHost(t.host(), t.access(), "PATCH", adminPath, "{\"siteTheme\":{\"tokens\":{\"text\":\"#777777\",\"bg\":\"#808080\"}}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['siteTheme.tokens.text']").exists());
        onHost(t.host(), t.access(), "PATCH", adminPath, "{\"siteTheme\":{\"tokens\":{\"btnBg\":\"#ffff00\",\"btnText\":\"#ffffff\"}}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['siteTheme.tokens.btnText']").exists());
        onHost(t.host(), t.access(), "PATCH", adminPath, "{\"siteTheme\":{\"tokens\":{\"text\":\"#ffffff\",\"bg\":\"#0b0d12\"}}}").andExpect(status().isOk());   // a dark site is fine

        // other fields of the same profile are not disturbed by a theme-less update, and null clears the theme
        onHost(t.host(), t.access(), "PATCH", adminPath, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.siteTheme.tokens.bg").value("#0b0d12"));
        onHost(t.host(), t.access(), "PATCH", adminPath, "{\"siteTheme\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.siteTheme").doesNotExist());
        onHost(t.host(), null, "GET", publicPath, null).andExpect(jsonPath(publicPrefix + "siteTheme").doesNotExist());
    }

    @Test
    void shopThemeIsValidatedPublicAndIsolated() throws Exception {
        Tenant a = onboard("STORE"), b = onboard("STORE");
        exercise(a, "/api/v1/store/profile", "/api/v1/shop/profile", b, "/api/v1/store/profile", "/api/v1/shop/profile", "$.profile.");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_logs WHERE action = 'SITE_THEME_CHANGED' AND entity_type = 'store_profile'").query(Long.class).single()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void clinicThemeIsValidatedPublicAndIsolated() throws Exception {
        Tenant a = onboard("CLINIC"), b = onboard("CLINIC");
        exercise(a, "/api/v1/clinic/profile", "/api/v1/clinic/public/profile", b, "/api/v1/clinic/profile", "/api/v1/clinic/public/profile", "$.");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_logs WHERE action = 'SITE_THEME_CHANGED' AND entity_type = 'clinic_profile'").query(Long.class).single()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void lawyerThemeIsValidatedPublicAndIsolated() throws Exception {
        Tenant a = lawyer(), b = lawyer();
        exercise(a, "/api/v1/lawyer/profile", "/api/v1/lawyer/public/profile", b, "/api/v1/lawyer/profile", "/api/v1/lawyer/public/profile", "$.");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_logs WHERE action = 'SITE_THEME_CHANGED' AND entity_type = 'tenant'").query(Long.class).single()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void aTamperedRowNeverReachesThePublicProfile() throws Exception {
        Tenant a = onboard("CLINIC");
        jdbc.sql("UPDATE medical.clinic_profiles SET site_theme = CAST(:j AS jsonb) WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)")
                .param("j", "{\"tokens\":{\"primary\":\"#112233\",\"bg\":\"red;}x{\",\"evil\":\"#000000\"}}").param("s", a.slug()).update();
        onHost(a.host(), null, "GET", "/api/v1/clinic/public/profile", null).andExpect(jsonPath("$.siteTheme.tokens.primary").value("#112233"))
                .andExpect(jsonPath("$.siteTheme.tokens.bg").doesNotExist()).andExpect(jsonPath("$.siteTheme.tokens.evil").doesNotExist());
    }
}
