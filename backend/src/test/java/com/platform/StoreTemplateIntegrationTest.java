package com.platform;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** The website template of a shop: 'original' until the owner picks another; only the known ones are accepted; visitors get it with the public profile. */
class StoreTemplateIntegrationTest extends IntegrationTestBase {
    @Test
    void ownerPicksATemplateAndVisitorsSeeIt() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/profile", null).andExpect(status().isOk()).andExpect(jsonPath("$.template").value("original"));
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/profile", "{\"template\":\"bloom\"}").andExpect(status().isOk()).andExpect(jsonPath("$.template").value("bloom"));
        onHost(t.host(), null, "GET", "/api/v1/shop/profile", null).andExpect(status().isOk()).andExpect(jsonPath("$.profile.template").value("bloom"));
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/profile", "{\"template\":\"neon-disco\"}").andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/profile", "{\"template\":\"original\"}").andExpect(status().isOk()).andExpect(jsonPath("$.template").value("original"));
    }
}
