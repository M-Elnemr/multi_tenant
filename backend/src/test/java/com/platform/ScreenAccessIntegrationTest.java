package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Owner gives an employee hidden / read only / read & write access per screen. */
class ScreenAccessIntegrationTest extends IntegrationTestBase {
    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    /** Invites an employee with the given access JSON, activates them and returns {userId, token}. */
    String[] employee(Tenant t, String accessJson) throws Exception {
        String phone = nextPhone();
        String inv = body(onHost(t.host(), t.access(), "POST", "/api/v1/tenant/members",
                "{\"firstName\":\"Emp\",\"phone\":\"%s\",\"access\":%s}".formatted(phone, accessJson)).andExpect(status().isCreated()));
        String token = JsonPath.read(body(onHost(t.host(), null, "POST", "/api/v1/auth/activate",
                "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"EmployeePass1\"}".formatted(phone, JsonPath.<String>read(inv, "$.activationPin"))).andExpect(status().isOk())), "$.accessToken");
        return new String[] {JsonPath.read(inv, "$.userId"), token};
    }

    @Test
    void shopEmployeeSeesOnlyAllowedScreensAndCannotWriteOnReadOnly() throws Exception {
        Tenant t = onboard("STORE");
        String[] e = employee(t, "{\"orders\":\"READ\",\"coupons\":\"WRITE\",\"inventory\":\"HIDDEN\"}");
        String h = t.host();

        // /auth/me lists exactly the allowed screens
        onHost(h, e[1], "GET", "/api/v1/auth/me", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.screens.orders").value("READ")).andExpect(jsonPath("$.screens.coupons").value("WRITE"))
                .andExpect(jsonPath("$.screens.inventory").doesNotExist()).andExpect(jsonPath("$.screens.products").doesNotExist());

        // read only: can read, cannot write
        onHost(h, e[1], "GET", "/api/v1/store/orders", null).andExpect(status().isOk());
        onHost(h, e[1], "GET", "/api/v1/store/returns", null).andExpect(status().isOk());
        onHost(h, e[1], "POST", "/api/v1/store/returns/" + java.util.UUID.randomUUID() + "/decision", "{\"status\":\"APPROVED\"}").andExpect(status().isForbidden());

        // read & write: both work
        onHost(h, e[1], "GET", "/api/v1/store/coupons", null).andExpect(status().isOk());
        onHost(h, e[1], "POST", "/api/v1/store/coupons", "{\"code\":\"emp10\",\"discountType\":\"PERCENT\",\"value\":10,\"perCustomerLimit\":1}").andExpect(status().isCreated());

        // hidden or not granted: forbidden
        onHost(h, e[1], "GET", "/api/v1/store/inventory", null).andExpect(status().isForbidden());
        onHost(h, e[1], "GET", "/api/v1/store/customers", null).andExpect(status().isForbidden());
        onHost(h, e[1], "GET", "/api/v1/tenant/members", null).andExpect(status().isForbidden());
        onHost(h, e[1], "PUT", "/api/v1/tenant/members/" + e[0] + "/access", "{\"access\":{\"inventory\":\"WRITE\"}}").andExpect(status().isForbidden());
    }

    @Test
    void ownerChangesAccessLaterAndCannotEditSelfOrUseUnknownScreens() throws Exception {
        Tenant t = onboard("STORE");
        String[] e = employee(t, "{\"coupons\":\"READ\"}");
        String h = t.host();
        onHost(h, e[1], "POST", "/api/v1/store/coupons", "{\"code\":\"x10\",\"discountType\":\"PERCENT\",\"value\":10,\"perCustomerLimit\":1}").andExpect(status().isForbidden());

        onHost(h, t.access(), "PUT", "/api/v1/tenant/members/" + e[0] + "/access", "{\"access\":{\"coupons\":\"WRITE\",\"reports\":\"READ\"}}").andExpect(status().isOk());
        // takes effect on the same token (permissions load per request)
        onHost(h, e[1], "POST", "/api/v1/store/coupons", "{\"code\":\"x10\",\"discountType\":\"PERCENT\",\"value\":10,\"perCustomerLimit\":1}").andExpect(status().isCreated());
        onHost(h, e[1], "GET", "/api/v1/store/reports/summary", null).andExpect(status().isOk());
        onHost(h, t.access(), "GET", "/api/v1/tenant/members", null).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.userId=='%s')].access.coupons".formatted(e[0])).value("WRITE"));

        onHost(h, t.access(), "PUT", "/api/v1/tenant/members/" + e[0] + "/access", "{\"access\":{\"nope\":\"READ\"}}").andExpect(status().isBadRequest());
        onHost(h, t.access(), "PUT", "/api/v1/tenant/members/" + e[0] + "/access", "{\"access\":{\"coupons\":\"ADMIN\"}}").andExpect(status().isBadRequest());
        // the screen catalogue the apps render
        String screens = body(onHost(h, t.access(), "GET", "/api/v1/tenant/members/screens", null).andExpect(status().isOk()));
        assertThat(screens).contains("\"key\":\"orders\"").doesNotContain("appointments");
    }

    @Test
    void clinicEmployeeReadOnlyExamAndHiddenSettings() throws Exception {
        Tenant t = onboard("CLINIC");
        String[] e = employee(t, "{\"patients\":\"READ\",\"appointments\":\"WRITE\"}");
        String h = t.host();
        onHost(h, e[1], "GET", "/api/v1/clinic/patients", null).andExpect(status().isOk());
        onHost(h, e[1], "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Nope\",\"phone\":\"%s\"}".formatted(nextPhone())).andExpect(status().isForbidden());
        onHost(h, e[1], "GET", "/api/v1/clinic/appointments", null).andExpect(status().isOk());
        onHost(h, e[1], "GET", "/api/v1/billing/subscription", null).andExpect(status().isForbidden());
        onHost(h, e[1], "PATCH", "/api/v1/clinic/profile", "{}").andExpect(status().isForbidden());
    }

    @Test
    void existingRoleStaffKeepFullAccessOnTheirScreens() throws Exception {
        Tenant t = onboard("STORE");
        String phone = nextPhone();
        String inv = body(onHost(t.host(), t.access(), "POST", "/api/v1/tenant/members",
                "{\"firstName\":\"Inv\",\"phone\":\"%s\",\"role\":\"INVENTORY_MANAGER\"}".formatted(phone)).andExpect(status().isCreated()));
        String token = JsonPath.read(body(onHost(t.host(), null, "POST", "/api/v1/auth/activate",
                "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"EmployeePass1\"}".formatted(phone, JsonPath.<String>read(inv, "$.activationPin"))).andExpect(status().isOk())), "$.accessToken");
        onHost(t.host(), token, "GET", "/api/v1/store/inventory", null).andExpect(status().isOk());
        onHost(t.host(), token, "GET", "/api/v1/auth/me", null).andExpect(jsonPath("$.screens.inventory").value("WRITE"));
    }
}
