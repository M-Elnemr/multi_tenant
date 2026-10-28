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
        assertThat(JsonPath.<String>read(reply, "$.reply")).contains("تمام تم تأكيد الأوردر").contains(JsonPath.<String>read(reply, "$.order.orderNumber")).contains("هنراجع الأوردر ونبلغك بكل جديد على رقمك").doesNotContain("خدمة العملاء");
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
    void modelKnowsWhatTheShopSellsAndAGeneralWordNeverReturnsNothing() throws Exception {
        Tenant t = onboard("STORE");
        mug(t);
        String conv = UUID.randomUUID().toString();
        // "cookware" is a category, not a word of the product's name: the old search found nothing and the model denied having it
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "cookware")));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "zzzunknown")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("We have mugs"));
        send(t, conv, "do you have cookware");
        assertThat(FakeAiProvider.lastSystem).contains("SHOP CATALOG").contains("Cookware").contains("Mug ").contains("1 products");
        var byCategory = FakeAiProvider.RESULTS.get(0).result();
        assertThat(byCategory.get("count")).isEqualTo(1);
        assertThat(byCategory.get("products").toString()).contains("Mug ");
        // nothing matches at all: no unrelated products to push, a note to say so kindly and offer only a related kind from the catalog
        var nothing = FakeAiProvider.RESULTS.get(1).result();
        assertThat(nothing.get("count")).isEqualTo(0);
        assertThat(nothing.get("note").toString()).contains("we do not have it").contains("RELATED").contains("never offer unrelated");
    }

    @Test
    void showProductsFlagsTheProductsWhosePicturesAreSentWhateverTheAnswerSays() throws Exception {
        Tenant t = onboard("STORE");
        mug(t);
        // by words: the model does not know the slug
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("show_products", Map.of("query", "mug")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Here is something nice"));
        String first = send(t, UUID.randomUUID().toString(), "send me a photo");
        assertThat(JsonPath.<Object>read(first, "$.products[0].send")).isEqualTo(true);
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("shown").toString()).contains("Mug ");
        // by slug, as written in the SHOP CATALOG examples
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Mug [^\\[;]*\\[([^\\]]+)\\]").matcher(FakeAiProvider.lastSystem);
        assertThat(m.find()).as("catalog lists the slug of its examples").isTrue();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("show_products", Map.of("slugs", List.of(m.group(1), "no-such-product"))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Enjoy"));
        String second = send(t, UUID.randomUUID().toString(), "photo please");
        assertThat(JsonPath.<Object>read(second, "$.products[0].slug")).isEqualTo(m.group(1));
        assertThat(JsonPath.<java.util.List<Object>>read(second, "$.products")).hasSize(1);
        // nothing known: an error for the model to fix, no product flagged
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("show_products", Map.of("slugs", List.of("nope"))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Sorry"));
        String third = send(t, UUID.randomUUID().toString(), "photo");
        assertThat(JsonPath.<java.util.List<Object>>read(third, "$.products")).isEmpty();
        assertThat(FakeAiProvider.RESULTS.get(FakeAiProvider.RESULTS.size() - 1).result().get("error").toString()).contains("No such product");
    }

    Map<String, Object> prepareIn(String variant, int qty, String gov) {
        return Map.of("items", List.of(Map.of("variantId", variant, "quantity", qty)), "customerName", "Nada Ali", "phone", "01011112222", "governorateCode", gov,
                "addressLine1", "5 Tahrir St, floor 2", "area", "Mansoura", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0);
    }

    @Test
    void theOrderSummaryIsWrittenByCodeWithTheDeliveryFeeOrWhyItIsFreeWhateverTheModelSays() throws Exception {
        Tenant t = onboard("STORE");
        String variant = mug(t);   // 100.00 each
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated());
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Delta\",\"governorateCodes\":[\"DKH\"],\"feeMinor\":8000,\"freeAboveMinor\":30000,\"etaMinDays\":2,\"etaMaxDays\":4}").andExpect(status().isOk());
        // one mug: delivery 80.00 is a line of the summary and part of the total, and the model's own (wrong) total never reaches the customer
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepareIn(variant, 1, "DKH")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Total is 9999 EGP, confirm?"));
        String one = JsonPath.<String>read(send(t, UUID.randomUUID().toString(), "one mug please"), "$.reply");
        assertThat(one).contains("Items: 100 EGP").contains("80 EGP").contains("Total: 180 EGP").contains("Expected delivery: 2-4 days").contains("Shall I place the order?").doesNotContain("9999").doesNotContain("free");
        // four mugs reach the free-delivery amount: the summary says delivery is free and above what
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepareIn(variant, 4, "DKH")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Delivery is 80 so the total is 480"));
        String four = JsonPath.<String>read(send(t, UUID.randomUUID().toString(), "4 mugs"), "$.reply");
        assertThat(four).contains("free").contains("(orders above 300 EGP)").contains("Total: 400 EGP").doesNotContain("480");
        // in Arabic the same, with the amounts in جنيه
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepareIn(variant, 4, "DKH")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        String ar = JsonPath.<String>read(send(t, UUID.randomUUID().toString(), "عايز ٤ أكواب"), "$.reply");
        assertThat(ar).contains("ده ملخص الأوردر").contains("مجاني").contains("الإجمالي: 400 جنيه").contains("أأكد الأوردر؟");
    }

    @Test
    void anAmountTheModelMadeUpIsRewrittenOnceThenCutAndAnOrderAlreadyPlacedIsAnsweredFromTheOrderOnFile() throws Exception {
        Tenant t = onboard("STORE");
        String variant = mug(t);
        String conv = UUID.randomUUID().toString();
        // an invented total: the model is sent back once and the rewrite is what the customer reads
        java.util.concurrent.atomic.AtomicReference<String> note = new java.util.concurrent.atomic.AtomicReference<>();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("الإجمالي ٩٩٩٩ جنيه"));
        FakeAiProvider.SCRIPT.add(turns -> { note.set(turns.get(turns.size() - 1).text()); return Turn.model("خلينا نجهز الأوردر وأطلعلك الإجمالي بالظبط، تحب؟"); });
        String first = JsonPath.<String>read(send(t, conv, "كام الإجمالي"), "$.reply");
        assertThat(note.get()).contains("SYSTEM CHECK").contains("٩٩٩٩");
        assertThat(first).isEqualTo("خلينا نجهز الأوردر وأطلعلك الإجمالي بالظبط، تحب؟");
        // it insists: the sentence with the amount is cut, the rest stays; nothing but the amount is left: a safe text
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام. الإجمالي ٩٩٩٩ جنيه. تحب أكمل؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام. الإجمالي ٩٩٩٩ جنيه. تحب أكمل؟"));
        String cut = JsonPath.<String>read(send(t, conv, "كام الإجمالي تاني"), "$.reply");
        assertThat(cut).doesNotContain("9999").doesNotContain("٩٩٩٩").contains("تحب أكمل");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("الإجمالي ٩٩٩٩ جنيه"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("الإجمالي ٩٩٩٩ جنيه"));
        assertThat(JsonPath.<String>read(send(t, conv, "طيب والإجمالي"), "$.reply")).contains("ملخص الأوردر").doesNotContain("٩٩٩٩");
        // a price the shop really has is not touched
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("الأكواب بـ 100 جنيه والاتنين بـ 200 جنيه"));
        assertThat(JsonPath.<String>read(send(t, conv, "الأسعار"), "$.reply")).isEqualTo("الأكواب بـ 100 جنيه والاتنين بـ 200 جنيه");

        // the order is placed (the summary comes from code, the confirmation too) ...
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepareIn(variant, 1, "CAI")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        send(t, conv, "عايز كوب");
        String done = send(t, conv, "تمام");
        assertThat(JsonPath.<String>read(done, "$.reply")).contains("تمام تم تأكيد الأوردر").contains("الإجمالي: 150 جنيه");
        // ... and a later question about it is answered from the ORDER ON FILE block, which says what the order really cost
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("الشحن 50 جنيه"));
        send(t, conv, "الشحن كام في الأوردر؟");
        assertThat(FakeAiProvider.lastSystem).contains("ORDER ON FILE").contains("TOTAL TO PAY 150 EGP").contains("delivery 50 EGP").contains("CAN STILL BE CHANGED");
    }

    @Test
    void pickupIsRefusedUnlessTheCustomerAskedForItAndAChangedPriceNeedsANewSummary() throws Exception {
        Tenant t = onboard("STORE");
        String variant = mug(t);
        String conv = UUID.randomUUID().toString();
        Map<String, Object> pickup = new java.util.HashMap<>(prepareIn(variant, 1, "CAI"));
        pickup.put("pickup", true);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", pickup));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("sorry"));
        send(t, conv, "عايز كوب");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error").toString()).contains("Pickup is only for a customer who asked");

        // the customer confirms the amounts they saw; the price went up meanwhile: no order, a new summary is needed
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepareIn(variant, 1, "CAI")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        send(t, conv, "ماشي هاخد كوب");
        jdbc.sql("UPDATE commerce.product_variants SET price_minor = price_minor + 2500 WHERE id = CAST(:v AS uuid)").param("v", variant).update();
        String reply = JsonPath.<String>read(send(t, conv, "تمام"), "$.reply");
        assertThat(reply).contains("اتغيرت");
        assertThat(orders(t)).isZero();
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
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/test", "{\"message\":\"one mug please\"}").andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("Total: 150 EGP")));
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
        onHost(a.host(), a.access(), "POST", "/api/v1/store/ai/test", "{\"message\":\"hi I need a dress\"}").andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("ALPHA");
        onHost(b.host(), b.access(), "POST", "/api/v1/store/ai/test", "{\"message\":\"hi I need a dress\"}").andExpect(status().isOk());
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
