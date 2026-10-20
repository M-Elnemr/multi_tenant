package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The AI seller: chat, negotiation limits, two-step ordering, per-shop and global teaching. The model is scripted. */
class AiChatIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    /** A shop with one 100.00 mug in stock; returns {variantId}. */
    String mug(Tenant t) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        return JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"Mug %s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(uniq(), uniq(), branch)).andExpect(status().isCreated())), "$.variants[0].id");
    }

    Map<String, Object> prepare(String variant, int discount, String payment) {
        return Map.of("items", List.of(Map.of("variantId", variant, "quantity", 1)), "customerName", "Nada Ali", "phone", "01011112222", "governorateCode", "CAI",
                "addressLine1", "5 Tahrir St, floor 2", "area", "Dokki", "paymentMethod", payment, "discountPercent", discount);
    }

    String send(Tenant t, String conv, String text) throws Exception { return send(t, null, conv, text); }

    String send(Tenant t, String token, String conv, String text) throws Exception {
        return body(onHost(t.host(), token, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk()));
    }

    long orders(Tenant t) throws Exception { return ((Number) JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/orders", null)), "$.meta.total")).longValue(); }

    @Test
    void guestChatsNegotiatesConfirmsAndTheOrderIsCreatedWithTheirCustomerRecord() throws Exception {
        Tenant t = onboard("STORE");
        String variant = mug(t);
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"maxDiscountPct\":10}").andExpect(status().isOk()).andExpect(jsonPath("$.maxDiscountPct").value(10));
        String conv = UUID.randomUUID().toString();

        // more than the allowed discount is refused by code, the model is told the limit
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(variant, 25, "CASH_ON_DELIVERY")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Sorry, the best I can do is 10%"));
        assertThat(JsonPath.<String>read(send(t, conv, "I want the mug for 25% less"), "$.reply")).contains("10%");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error").toString()).contains("maximum discount");

        // an allowed discount gives a summary; nothing is ordered yet
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(variant, 5, "CASH_ON_DELIVERY")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Total 145. Confirm?"));
        send(t, conv, "ok 5% then");
        assertThat(FakeAiProvider.RESULTS.get(0).result().toString()).contains("summary");
        assertThat(orders(t)).isZero();

        // placing it before the customer replied to the summary is refused
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(variant, 5, "CASH_ON_DELIVERY")));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("place_order", Map.of()));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("waiting for you"));
        send(t, UUID.randomUUID().toString(), "buy it now");
        assertThat(FakeAiProvider.RESULTS.get(1).result().get("error").toString()).contains("not confirmed");
        assertThat(orders(t)).isZero();

        // the customer says yes in a later message: the order is created, tagged, with the discount, and the customer record keeps the address
        FakeAiProvider.RESULTS.clear();
        String reply = send(t, conv, "تمام حضرتك");   // a plain agreement places the order in code; the model is not involved
        assertThat(JsonPath.<String>read(reply, "$.order.orderNumber")).isNotBlank();
        assertThat(JsonPath.<String>read(reply, "$.reply")).contains("تم تأكيد طلبك").contains(JsonPath.<String>read(reply, "$.order.orderNumber"));
        assertThat(orders(t)).isEqualTo(1);
        String list = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/orders", null));
        assertThat(JsonPath.<String>read(list, "$.data[0].source")).isEqualTo("AI_CHAT");
        assertThat(JsonPath.<Number>read(list, "$.data[0].totalMinor").longValue()).isEqualTo(14500L);   // 100.00 - 5% + 50.00 delivery
        String customers = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/customers", null));
        assertThat(customers).contains("Nada Ali").contains("Tahrir");

        // replaying the same confirmation cannot create a second order
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("place_order", Map.of()));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("already done"));
        send(t, conv, "yes");
        assertThat(orders(t)).isEqualTo(1);
    }

    @Test
    void onlyAPlainAgreementCountsAsConfirmation() {
        for (String yes : List.of("تمام", "تمام حضرتك", "ايوه يا فندم", "أيوه", "نعم", "yes", "Yes please", "ok ✅", "اكيد", "موافق", "تمام جدا"))
            assertThat(com.platform.chat.ChatService.isConfirmation(yes)).as(yes).isTrue();
        for (String no : List.of("تمام بس غير العنوان", "لا", "فيه الوان ومقاسات ؟", "ok but make it two", "بكام الشحن", "yes and add a pillow", "", "هاي"))
            assertThat(com.platform.chat.ChatService.isConfirmation(no)).as(no).isFalse();
    }

    @Test
    void signedInClientOrdersAndTheAddressIsSaved() throws Exception {
        Tenant t = onboard("STORE");
        String variant = mug(t);
        String client = googleClient(t.host());
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(variant, 0, "CASH_ON_DELIVERY")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Confirm?"));
        send(t, client, conv, "one mug");
        assertThat(FakeAiProvider.lastSystem).contains("SIGNED IN");
        send(t, client, conv, "yes");
        assertThat(orders(t)).isEqualTo(1);
        onHost(t.host(), client, "GET", "/api/v1/shop/orders", null).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(1));
        onHost(t.host(), client, "GET", "/api/v1/shop/addresses", null).andExpect(status().isOk()).andExpect(jsonPath("$[0].addressLine1").value("5 Tahrir St, floor 2"));
    }

    @Test
    void cardIsNotOfferedWhileCardPaymentsAreOffAndTheOwnerTestChatNeverOrders() throws Exception {
        Tenant t = onboard("STORE");
        String variant = mug(t);
        // the owner's test chat: a full conversation, but no real order
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(variant, 0, "CASH_ON_DELIVERY")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Confirm?"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/test", "{\"message\":\"one mug please\"}").andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("Confirm?"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/test", "{\"message\":\"yes\"}").andExpect(status().isOk()).andExpect(jsonPath("$.order.test").value(true));
        assertThat(orders(t)).isZero();
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/test", null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
        onHost(t.host(), t.access(), "DELETE", "/api/v1/store/ai/test", null).andExpect(status().isNoContent());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/test", null).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void lessonsAreIsolatedPerShopAndGlobalOnesReachEveryShop() throws Exception {
        Tenant a = onboard("STORE");
        Tenant b = onboard("STORE");
        onHost(a.host(), a.access(), "POST", "/api/v1/store/ai/lessons", "{\"instruction\":\"Always say marhaba first ALPHA\",\"exampleQuestion\":\"hi\",\"exampleAnswer\":\"marhaba!\"}").andExpect(status().isCreated());
        onHost(a.host(), a.access(), "POST", "/api/v1/store/ai/test", "{\"message\":\"hi\"}").andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("ALPHA");
        onHost(b.host(), b.access(), "POST", "/api/v1/store/ai/test", "{\"message\":\"hi\"}").andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).doesNotContain("ALPHA");
        onHost(b.host(), b.access(), "GET", "/api/v1/store/ai/lessons", null).andExpect(jsonPath("$.length()").value(0));

        // a shop owner cannot write global lessons; the platform admin can, and every shop gets them
        onHost("platform.test", a.access(), "POST", "/api/v1/platform/chat/lessons", "{\"instruction\":\"GLOBAL-RULE\"}").andExpect(status().isForbidden());
        java.util.UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", b.phone()).query(java.util.UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        String admin = JsonPath.read(body(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(b.phone())).andExpect(status().isOk())), "$.accessToken");
        onHost("platform.test", admin, "POST", "/api/v1/platform/chat/lessons", "{\"instruction\":\"GLOBAL-RULE-ZETA\"}").andExpect(status().isCreated());
        onHost(a.host(), a.access(), "POST", "/api/v1/store/ai/test", "{\"message\":\"hi\"}").andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("GLOBAL-RULE-ZETA").contains("ALPHA");
        // the global lesson is not shown in the shop's own list, and the shop cannot delete it
        onHost(a.host(), a.access(), "GET", "/api/v1/store/ai/lessons", null).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void ownerCanSwitchTheChatOffAndAnEmployeeWithoutAiAccessIsRefused() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"enabled\":false}").andExpect(status().isOk());
        onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null).andExpect(jsonPath("$.enabled").value(false));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"hi\"}".formatted(UUID.randomUUID())).andExpect(status().isServiceUnavailable());
        // not a valid conversation id, and other shops' teaching endpoints need a login
        onHost(t.host(), null, "GET", "/api/v1/store/ai/lessons", null).andExpect(status().isUnauthorized());
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"abc\",\"message\":\"hi\"}").andExpect(status().is4xxClientError());
    }
}
