package com.platform;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * {@code app.domains.registrar.enabled} defaults to false (no override here, unlike {@link DomainPurchaseIntegrationTest}):
 * search returns no results with the flag surfaced, and attempting to buy refuses with 409 REGISTRAR_NOT_CONFIGURED -
 * never a fake success, even if a caller supplies a well-formed body.
 */
class DomainPurchaseDisabledIntegrationTest extends IntegrationTestBase {
    @Test
    void searchAndPurchaseBothRefuseCleanlyWhileTheRegistrarIsNotConfigured() throws Exception {
        Tenant shop = onboard("STORE");
        onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/search?q=whatever" + uniq(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrarEnabled").value(false)).andExpect(jsonPath("$.results.length()").value(0));

        onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/pricing", null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].registerPriceMinor").value(org.hamcrest.Matchers.nullValue()));

        onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + java.util.UUID.randomUUID() + "\",\"years\":1,\"registrantContactId\":\"" + java.util.UUID.randomUUID() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGISTRAR_NOT_CONFIGURED"));
    }

    @Test
    void merchantWithoutThePermissionIsForbidden() throws Exception {
        // domains.purchase is OWNER-only (mirrors domain.manage's role set); a staff role without it cannot even search
        Tenant shop = onboard("STORE");
        onHost(shop.host(), null, "GET", "/api/v1/store/domains/search?q=x", null).andExpect(status().isUnauthorized());
    }
}
