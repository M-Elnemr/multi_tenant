package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.MetaChannelService;
import com.platform.chat.WaReportService;
import com.platform.copilot.CopilotData;
import com.platform.copilot.CopilotService;
import com.platform.marketing.AdsAutopilotService;
import com.platform.marketing.AdsService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The AI seller's skills, the WhatsApp daily report, the Copilot and the marketing autopilot. The model, Meta and WhatsApp are faked. */
class AiSellerCopilotAutopilotIntegrationTest extends IntegrationTestBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    @Autowired JdbcClient jdbc;
    @Autowired WaReportService waReport;
    @Autowired MetaChannelService meta;
    @Autowired CopilotService copilot;
    @Autowired CopilotData copilotData;
    @Autowired AdsService ads;
    @Autowired AdsAutopilotService autopilot;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMetaGraph.reset(); FakeImageGenerator.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String productId, String variantId, String productName) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String name = "Linen Shirt " + uniq();
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"%s","brand":"Acme","description":"Soft breathable linen shirt","variants":[{"sku":"S-%s","priceMinor":45000,"compareAtPriceMinor":60000,"stock":[{"branchId":"%s","quantity":9}]}]}
                """.formatted(name, uniq(), branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        return new Shop(t, id, JsonPath.read(p, "$.id"), JsonPath.read(p, "$.variants[0].id"), name);
    }

    String chat(Shop s, String conv, String text) throws Exception {
        return ok(onHost(s.t().host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk()));
    }

    // ---- Phase 2: the seller ---------------------------------------------------------------------------------------------------

    @Test
    void theSellerSellsLikeAPersonRemembersAndFlagsWhatItCannotAnswer() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("اهلا بيك"));
        chat(s, conv, "السلام عليكم عايز فستان");
        assertThat(FakeAiProvider.lastSystem).contains("HOW YOU SELL").contains("remember_customer").contains("schedule_follow_up").doesNotContain("customer-support");
        assertThat(FakeAiProvider.lastSystem).doesNotContain("will contact you").doesNotContain("خدمة العملاء");

        // it keeps a short note about the customer and uses it next time; phone numbers are never saved
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("remember_customer", Map.of("note", "wears size L, likes dark colours")));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("remember_customer", Map.of("note", "call me on 01012345678")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        chat(s, conv, "انا بلبس لارج");
        assertThat(FakeAiProvider.RESULTS.get(1).result().get("error").toString()).contains("phone");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat(s, conv, "عايز حاجة جديدة");
        assertThat(FakeAiProvider.lastSystem).contains("wears size L");

        // a question it cannot answer is flagged quietly for the owner
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "do you make custom sizes?")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("مش عندي التفصيلة دي دلوقتي"));
        chat(s, conv, "بتعملوا مقاس خاص؟");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/ai/unanswered", null).andExpect(status().isOk()).andExpect(jsonPath("$[0].question").value("do you make custom sizes?"));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/ai/usage", null).andExpect(jsonPath("$.unanswered").value(1)).andExpect(jsonPath("$.usedThisMonth").value(4));

        // an order status needs the right phone: a wrong phone looks exactly like a missing order
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("order_status", Map.of("orderNumber", "NOPE-1", "phone", "01099998888")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("not found"));
        chat(s, conv, "فين طلبي");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error").toString()).contains("No order matches");

        // delivery quote and related products come from tools
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("delivery_quote", Map.of("governorateCode", "CAI")));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("recommend_related", Map.of("slug", "x")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("done"));
        chat(s, conv, "التوصيل للقاهرة؟");
        assertThat(FakeAiProvider.RESULTS).hasSize(2);

        // follow-ups work only inside a chat app, not on the website
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("schedule_follow_up", Map.of("hours", 5, "message", "still thinking?")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat(s, conv, "هفكر");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error").toString()).contains("WhatsApp");
    }

    @Test
    void theMonthlyConversationLimitStopsTheModelPoliteley() throws Exception {
        Shop s = shop();
        jdbc.sql("INSERT INTO chat.usage (tenant_id, month, ai_messages) VALUES (:t, date_trunc('month', now() AT TIME ZONE 'UTC')::date, 5000)").param("t", s.id()).update();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("should never be said"));
        String reply = chat(s, UUID.randomUUID().toString(), "hello");
        assertThat(JsonPath.<String>read(reply, "$.reply")).contains("limit is reached").doesNotContain("should never be said");
        assertThat(FakeAiProvider.SCRIPT).hasSize(1);
    }

    @Test
    void theEvaluationSetHasSeventyFiveConversationsWithRubrics() throws Exception {
        JsonNode set = JSON.readTree(getClass().getResourceAsStream("/ai-eval.json"));
        assertThat(set.path("conversations")).hasSize(75);
        for (JsonNode c : set.path("conversations")) {
            assertThat(c.path("customer").size()).as("customer turns of #" + c.path("id")).isGreaterThan(0);
            assertThat(c.path("expect").size()).as("rubric of #" + c.path("id")).isGreaterThan(0);
            if (c.has("shop")) assertThat(c.path("shop").asText()).isIn("beit-elshawarma", "nile-style");
        }
        assertThat(set.toString()).contains("injection").contains("out_of_stock").contains("order_status").contains("cod_fear").contains("who_is_it_for").contains("respect_name").contains("burst");
    }

    // ---- Phase 3: WhatsApp daily report ----------------------------------------------------------------------------------------

    String webhook(String from, String id, String type, String payload) throws Exception {
        String message = "text".equals(type) ? "{\"from\":\"%s\",\"id\":\"%s\",\"type\":\"text\",\"text\":{\"body\":\"%s\"}}".formatted(from, id, payload)
                : "{\"from\":\"%s\",\"id\":\"%s\",\"type\":\"interactive\",\"interactive\":{\"button_reply\":{\"id\":\"%s\",\"title\":\"x\"}}}".formatted(from, id, payload);
        String raw = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"999000111\"},\"messages\":[" + message + "]}}]}]}";
        meta.handle(raw);
        return raw;
    }

    long sentTo(String phone) { return FakeMetaGraph.SENT.stream().filter(x -> phone.equals(x.body().get("to"))).count(); }

    List<String> sentTexts() {
        return FakeMetaGraph.SENT.stream().map(x -> x.body().toString()).toList();
    }

    @Test
    void aShopLinksItsNumberByMessagingUsAndGetsOneFreeReportPerNight() throws Exception {
        Shop s = shop();
        String phone = "2010" + (10_000_000L + System.nanoTime() % 80_000_000L);
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/whatsapp-report", null).andExpect(jsonPath("$.linked").value(false)).andExpect(jsonPath("$.configured").value(true));
        String code = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/whatsapp-report/link-code", "{}").andExpect(status().isCreated())), "$.code");
        assertThat(code).matches("MNS\\d{6}");

        // a wrong code does nothing; the right one links this phone to this shop only
        webhook(phone, "wamid.A1" + uniq(), "text", "MNS000000");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/whatsapp-report", null).andExpect(jsonPath("$.linked").value(false));
        webhook(phone, "wamid.A2" + uniq(), "text", "hi " + code.toLowerCase().replace("mns", "MNS "));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/whatsapp-report", null).andExpect(jsonPath("$.linked").value(true)).andExpect(jsonPath("$.windowOpen").value(true)).andExpect(jsonPath("$.sendAfter").value("23:59"));
        assertThat(sentTexts()).anyMatch(x -> x.contains("تم الربط"));
        webhook(phone, "wamid.A3" + uniq(), "text", code);   // the code works once
        assertThat(jdbc.sql("SELECT count(*) FROM whatsapp.contacts WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single()).isEqualTo(1);

        // before 23:59 nothing is sent; at 23:59 shop time one report with a button, never two for the same day
        FakeMetaGraph.SENT.clear();
        LocalDate today = LocalDate.now(CAIRO);
        waReport.runDue(today.atTime(20, 0).atZone(CAIRO).toInstant());
        assertThat(sentTo(phone)).isZero();
        Instant night = today.atTime(23, 59, 30).atZone(CAIRO).toInstant();
        jdbc.sql("UPDATE whatsapp.contacts SET last_inbound_at = :t WHERE tenant_id = :i").param("t", Timestamp.from(night.minusSeconds(3600))).param("i", s.id()).update();
        waReport.runDue(night);
        waReport.runDue(night.plusSeconds(300));
        assertThat(sentTo(phone)).isEqualTo(1);   // never two for the same day
        var sentReport = FakeMetaGraph.SENT.stream().filter(x -> phone.equals(x.body().get("to"))).findFirst().orElseThrow();
        assertThat(sentReport.body().toString()).contains("interactive").contains("REPORT_TOMORROW").contains("تقرير").contains("٣ حاجات");
        assertThat(sentReport.path()).isEqualTo("999000111/messages");
        assertThat(sentReport.token()).isEqualTo("PLATFORM-TOKEN-0123456789");

        // the next night the window has closed (no message from the shop for over a day): no paid template is sent, the shop is asked to ping
        FakeMetaGraph.SENT.clear();
        Instant next = today.plusDays(1).atTime(23, 59, 30).atZone(CAIRO).toInstant();
        jdbc.sql("UPDATE whatsapp.contacts SET last_inbound_at = :t WHERE tenant_id = :i").param("t", Timestamp.from(next.minusSeconds(30 * 3600))).param("i", s.id()).update();
        waReport.runDue(next);
        assertThat(sentTo(phone)).isZero();
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/whatsapp-report", null).andExpect(jsonPath("$.needsPing").value(true));
        assertThat(jdbc.sql("SELECT status FROM whatsapp.report_log WHERE tenant_id = :t AND day = :d").param("t", s.id()).param("d", java.sql.Date.valueOf(today.plusDays(1))).query(String.class).single()).isEqualTo("WINDOW_CLOSED");

        // tapping the button (any message) reopens the window; the missed report goes out in the early-morning catch-up
        webhook(phone, "wamid.A4" + uniq(), "button", "REPORT_TOMORROW");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/whatsapp-report", null).andExpect(jsonPath("$.needsPing").value(false));
        Instant morning = today.plusDays(2).atTime(0, 30).atZone(CAIRO).toInstant();
        jdbc.sql("UPDATE whatsapp.contacts SET last_inbound_at = :t WHERE tenant_id = :i").param("t", Timestamp.from(morning.minusSeconds(600))).param("i", s.id()).update();
        FakeMetaGraph.SENT.clear();
        waReport.runDue(morning);
        assertThat(sentTo(phone)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM whatsapp.report_log WHERE tenant_id = :t AND day = :d").param("t", s.id()).param("d", java.sql.Date.valueOf(today.plusDays(1))).query(String.class).single()).isEqualTo("SENT");

        // settings: time is an end-of-day time; switching off stops reports; another shop cannot touch this one
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/whatsapp-report", "{\"sendAfter\":\"09:00\"}").andExpect(status().isBadRequest());
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/whatsapp-report", "{\"enabled\":false,\"sendAfter\":\"22:30\",\"language\":\"en\"}").andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.sendAfter").value("22:30"));
        FakeMetaGraph.SENT.clear();
        waReport.runDue(today.plusDays(5).atTime(23, 0).atZone(CAIRO).toInstant());
        assertThat(sentTo(phone)).isZero();
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/whatsapp-report", null).andExpect(jsonPath("$.linked").value(false));
        onHost(s.t().host(), s.t().access(), "DELETE", "/api/v1/store/whatsapp-report", null).andExpect(status().isNoContent());
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/whatsapp-report", null).andExpect(jsonPath("$.linked").value(false));
    }

    // ---- Phase 4: Copilot ---------------------------------------------------------------------------------------------------------

    void sell(Shop s, int quantity, int daysAgo) {
        UUID order = jdbc.sql("""
                INSERT INTO commerce.orders (tenant_id, order_number, status, payment_status, payment_method, currency, subtotal_minor, total_minor, customer_name_snapshot, customer_phone_snapshot, created_at)
                VALUES (:t, :n, 'ARRIVED', 'PAID', 'CASH_ON_DELIVERY', 'EGP', :s, :s, 'Buyer', '01011112222', now() - make_interval(days => :d)) RETURNING id
                """).param("t", s.id()).param("n", "T-" + uniq()).param("s", 45000L * quantity).param("d", daysAgo).query(UUID.class).single();
        jdbc.sql("INSERT INTO commerce.order_items (order_id, tenant_id, product_id, variant_id, sku_snapshot, product_name_snapshot, unit_price_minor, quantity, line_total_minor) VALUES (:o, :t, :p, :v, 'S', :n, 45000, :q, :l)")
                .param("o", order).param("t", s.id()).param("p", UUID.fromString(s.productId())).param("v", UUID.fromString(s.variantId())).param("n", s.productName()).param("q", quantity).param("l", 45000L * quantity).update();
    }

    @Test
    void theCopilotForecastsDemandAndAlwaysGivesThreeActions() throws Exception {
        Shop s = shop();
        assertThat(copilot.dailyActions(s.id(), "ar")).hasSize(3);   // even with no sales at all
        assertThat(copilot.dailyActions(s.id(), "en")).extracting(CopilotService.Action::kind).containsExactly("RESTOCK", "PROMOTE", "FOLLOW_UP");

        sell(s, 20, 2);   // 20 sold in two days against 9 in stock
        var reorder = copilotData.reorderSuggestions(s.id());
        assertThat(reorder).hasSize(1);
        assertThat(reorder.get(0).get("name")).isEqualTo(s.productName());
        assertThat(reorder.get(0).get("urgent")).isEqualTo(true);
        assertThat(((Number) reorder.get(0).get("suggestedOrderQuantity")).longValue()).isGreaterThan(20);
        var actions = copilot.dailyActions(s.id(), "ar");
        assertThat(actions.get(0).kind()).isEqualTo("RESTOCK");
        assertThat(actions.get(0).title()).contains(s.productName());
        assertThat(actions.get(1).title()).contains(s.productName());   // the best seller is the one to promote

        // sales this week against last week
        sell(s, 5, 9);
        Map<String, Object> week = copilotData.period(s.id(), 7);
        assertThat(((Number) week.get("orders")).longValue()).isEqualTo(1);
        assertThat(((Number) week.get("previousOrders")).longValue()).isEqualTo(1);
        assertThat(week.get("salesChangePercent")).isEqualTo(300.0);

        // the owner asks; the model reads the numbers through a tool and the answer is stored
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("compare_periods", Map.of("days", 7)));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("المبيعات زادت ٣٠٠٪"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/copilot/ask", "{\"question\":\"ليه المبيعات زادت؟\"}").andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("المبيعات زادت ٣٠٠٪"));
        assertThat(FakeAiProvider.RESULTS.get(0).result().toString()).contains("salesChangePercent");
        assertThat(FakeAiProvider.lastSystem).contains("ONLY numbers returned by your tools");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/copilot/messages", null).andExpect(jsonPath("$.length()").value(2));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/copilot/overview?lang=en", null).andExpect(status().isOk()).andExpect(jsonPath("$.actions.length()").value(3)).andExpect(jsonPath("$.reorder.length()").value(1));
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/copilot/messages", null).andExpect(jsonPath("$.length()").value(0));
        onHost(s.t().host(), null, "GET", "/api/v1/store/copilot/overview", null).andExpect(status().isUnauthorized());
    }

    @Test
    void priceIdeasStayInsideTheLimitsAndAboveCost() throws Exception {
        Shop s = shop();
        jdbc.sql("UPDATE commerce.inventory_items SET quantity_on_hand = 12 WHERE variant_id = :v").param("v", UUID.fromString(s.variantId())).update();
        jdbc.sql("UPDATE commerce.product_variants SET cost_price_minor = 41000 WHERE product_id = :p").param("p", UUID.fromString(s.productId())).update();
        assertThat(copilotData.priceSuggestions(s.id(), 20)).isEmpty();   // cost 410.00 on a 450.00 price leaves no room for a discount
        jdbc.sql("UPDATE commerce.product_variants SET cost_price_minor = 35000 WHERE product_id = :p").param("p", UUID.fromString(s.productId())).update();
        var ideas = copilotData.priceSuggestions(s.id(), 20);   // nothing sold in 28 days with 12 in stock: a slow seller
        assertThat(ideas).hasSize(1);
        double to = ((Number) ideas.get(0).get("to")).doubleValue();
        assertThat(to).isGreaterThanOrEqualTo(350.0 * 1.1 - 0.01);   // never below cost plus 10 percent
        assertThat(((Number) ideas.get(0).get("percent")).intValue()).isBetween(5, 15);
    }

    // ---- Phase 5: marketing autopilot -------------------------------------------------------------------------------------------

    void connectPage(Tenant t) throws Exception {
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"FACEBOOK\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted("4" + System.nanoTime())).andExpect(status().isCreated());
    }

    @Test
    void theWeeklyPlanIsSuggestedEditedApprovedAndNothingIsPostedWithoutApproval() throws Exception {
        Shop s = shop();
        connectPage(s.t());
        String items = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/plan/generate", "{\"language\":\"ar\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(7)));
        assertThat((List<?>) JsonPath.read(items, "$[?(@.status=='SUGGESTED')]")).hasSize(7);
        assertThat((List<?>) JsonPath.read(items, "$[?(@.kind=='SHOP')]")).hasSize(1);
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/plan/generate", "{}").andExpect(status().isConflict());   // the week is already planned
        assertThat(jdbc.sql("SELECT count(*) FROM marketing.posts WHERE tenant_id = :t AND status IN ('SCHEDULED','POSTED','POSTING')").param("t", s.id()).query(Long.class).single()).isZero();

        String id = JsonPath.read(items, "$[0].id");
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/marketing/plan/" + id, "{\"caption\":\"My own words\"}").andExpect(jsonPath("$.caption").value("My own words"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/plan/" + id + "/approve", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
        assertThat(jdbc.sql("SELECT count(*) FROM marketing.posts WHERE tenant_id = :t AND status = 'SCHEDULED' AND caption LIKE 'My own words%'").param("t", s.id()).query(Long.class).single()).isEqualTo(1);
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/marketing/plan/" + id, "{\"caption\":\"too late\"}").andExpect(status().isConflict());
        String id2 = JsonPath.read(items, "$[1].id");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/plan/" + id2 + "/skip", "{}").andExpect(jsonPath("$.status").value("SKIPPED"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/plan/" + id + "/video", "{}").andExpect(status().isServiceUnavailable());   // video is off on this server

        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "POST", "/api/v1/store/marketing/plan/" + id + "/approve", "{}").andExpect(status().isNotFound());
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/marketing/plan", null).andExpect(jsonPath("$.items.length()").value(0));
    }

    /** A launched campaign (active on the fake Meta) with a 150.00 daily budget; the fake reports 45 clicks for 31.50, so the cost per click is 0.70. */
    String campaign(Shop s) throws Exception {
        connectPage(s.t());
        Map<String, Object> acct = ads.connectAccount(s.id(), null, "act_" + (1000 + (System.nanoTime() % 8000)), "Shop ad account", "USER-TOKEN-0123456789012345", "EGP");
        String body = """
                {"accountId":"%s","productId":"%s","name":"Summer linen","objective":"OUTCOME_TRAFFIC","dailyBudgetMinor":15000,"durationDays":10,"countries":["EG"],"ageMin":22,"ageMax":45,"genders":[],
                 "interests":["Shopping"],"headline":"Summer linen","primaryText":"Light and cool","cta":"SHOP_NOW","imageUrl":"https://cdn.example.com/a.jpg","launch":true}
                """.formatted(acct.get("id"), s.productId());
        return JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/campaigns", body).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))), "$.id");
    }

    long budget(String campaign) { return jdbc.sql("SELECT daily_budget_minor FROM marketing.campaigns WHERE id = :i").param("i", UUID.fromString(campaign)).query(Long.class).single(); }

    String statusOf(String campaign) { return jdbc.sql("SELECT status FROM marketing.campaigns WHERE id = :i").param("i", UUID.fromString(campaign)).query(String.class).single(); }

    @Test
    void theOptimizerStaysInsideTheGuardrailsLogsEverythingAndCanBeUndone() throws Exception {
        Shop s = shop();
        String camp = campaign(s);
        String base = "/api/v1/store/marketing";

        // it cannot be switched on without a target, and caps must make sense
        onHost(s.t().host(), s.t().access(), "PUT", base + "/guardrails", "{\"enabled\":true}").andExpect(status().isBadRequest());
        onHost(s.t().host(), s.t().access(), "PUT", base + "/guardrails", "{\"dailyCapMinor\":50000,\"monthlyCapMinor\":1000}").andExpect(status().isBadRequest());

        // switched off: nothing happens
        assertThat(autopilot.optimize(s.id())).isEmpty();

        // a winning ad (0.70 per click against a 1.50 target) is raised by the step, but only because both caps are set
        onHost(s.t().host(), s.t().access(), "PUT", base + "/guardrails", "{\"enabled\":true,\"targetCpcMinor\":150,\"dailyCapMinor\":20000,\"monthlyCapMinor\":100000000,\"maxStepPct\":20}").andExpect(status().isOk());
        assertThat(autopilot.optimize(s.id())).hasSize(1);
        assertThat(budget(camp)).isEqualTo(18000L);
        assertThat(autopilot.optimize(s.id())).isEmpty();   // 21 600 would go above the 200.00 daily cap: no second raise
        assertThat(budget(camp)).isEqualTo(18000L);
        var log = ok(onHost(s.t().host(), s.t().access(), "GET", base + "/ad-actions", null).andExpect(jsonPath("$[0].kind").value("RAISE_BUDGET")));
        long actionId = ((Number) JsonPath.read(log, "$[0].id")).longValue();
        onHost(s.t().host(), s.t().access(), "POST", base + "/ad-actions/" + actionId + "/undo", "{}").andExpect(status().isOk());
        assertThat(budget(camp)).isEqualTo(15000L);
        onHost(s.t().host(), s.t().access(), "POST", base + "/ad-actions/" + actionId + "/undo", "{}").andExpect(status().isConflict());

        // with no caps set it never raises
        onHost(s.t().host(), s.t().access(), "PUT", base + "/guardrails", "{\"dailyCapMinor\":0,\"monthlyCapMinor\":0}").andExpect(status().isOk());
        assertThat(autopilot.optimize(s.id())).isEmpty();
        assertThat(budget(camp)).isEqualTo(15000L);

        // budgets above the daily cap are lowered to fit
        onHost(s.t().host(), s.t().access(), "PUT", base + "/guardrails", "{\"targetCpcMinor\":100,\"dailyCapMinor\":10000,\"monthlyCapMinor\":100000000}").andExpect(status().isOk());
        var acts = autopilot.optimize(s.id());
        assertThat(acts).hasSize(1);
        assertThat(acts.get(0).get("kind")).isEqualTo("LOWER_BUDGET");
        assertThat(budget(camp)).isEqualTo(10000L);

        // an ad far above the target cost per click is paused, and the owner can switch it back on
        onHost(s.t().host(), s.t().access(), "PUT", base + "/guardrails", "{\"targetCpcMinor\":20,\"dailyCapMinor\":0,\"monthlyCapMinor\":0}").andExpect(status().isOk());
        assertThat(autopilot.optimize(s.id())).extracting(a -> a.get("kind")).contains("PAUSE");
        assertThat(statusOf(camp)).isEqualTo("PAUSED");
        long pauseId = ((Number) ((List<?>) JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "GET", base + "/ad-actions", null)), "$[?(@.kind=='PAUSE')].id")).get(0)).longValue();
        onHost(s.t().host(), s.t().access(), "POST", base + "/ad-actions/" + pauseId + "/undo", "{}").andExpect(status().isOk());
        assertThat(statusOf(camp)).isEqualTo("ACTIVE");

        // another shop sees none of it
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "GET", base + "/ad-actions", null).andExpect(jsonPath("$.length()").value(0));
        onHost(other.t().host(), other.t().access(), "POST", base + "/ad-actions/" + pauseId + "/undo", "{}").andExpect(status().isNotFound());
    }

    @Test
    void threeAdTextsRunTogetherAndTheCheapestPerClickIsKept() throws Exception {
        Shop s = shop();
        String camp = campaign(s);
        FakeMetaGraph.SENT.clear();
        String made = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/campaigns/" + camp + "/variants", "{\"language\":\"ar\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(3)));
        assertThat(made).contains("وادفع").doesNotContain("خصم");   // the fallback texts never invent a discount
        assertThat(FakeMetaGraph.SENT.stream().filter(x -> x.path().endsWith("/ads")).count()).isEqualTo(3);
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/campaigns/" + camp + "/variants", "{}").andExpect(status().isConflict());

        // too early and too little evidence: nothing is decided
        autopilot.decide(s.id(), UUID.fromString(camp));
        assertThat(jdbc.sql("SELECT count(*) FROM marketing.copy_variants WHERE campaign_id = :c AND status = 'RUNNING'").param("c", UUID.fromString(camp)).query(Long.class).single()).isEqualTo(3);

        List<UUID> ids = jdbc.sql("SELECT id FROM marketing.copy_variants WHERE campaign_id = :c ORDER BY created_at, id").param("c", UUID.fromString(camp)).query(UUID.class).list();
        String[] results = {"{\"clicks\":\"50\",\"spend\":\"25.00\",\"impressions\":\"1000\"}", "{\"clicks\":\"40\",\"spend\":\"40.00\",\"impressions\":\"900\"}", "{\"clicks\":\"30\",\"spend\":\"45.00\",\"impressions\":\"800\"}"};
        for (int i = 0; i < 3; i++) jdbc.sql("UPDATE marketing.copy_variants SET insights = CAST(:i AS jsonb) WHERE id = :v").param("i", results[i]).param("v", ids.get(i)).update();
        autopilot.decide(s.id(), UUID.fromString(camp));
        assertThat(jdbc.sql("SELECT status FROM marketing.copy_variants WHERE id = :v").param("v", ids.get(0)).query(String.class).single()).isEqualTo("WINNER");
        assertThat(jdbc.sql("SELECT count(*) FROM marketing.copy_variants WHERE campaign_id = :c AND status = 'PAUSED'").param("c", UUID.fromString(camp)).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM marketing.ad_actions WHERE campaign_id = :c AND kind = 'PAUSE_VARIANT'").param("c", UUID.fromString(camp)).query(Long.class).single()).isEqualTo(2);
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/marketing/campaigns/" + camp + "/variants", null).andExpect(jsonPath("$.length()").value(3));
    }
}
