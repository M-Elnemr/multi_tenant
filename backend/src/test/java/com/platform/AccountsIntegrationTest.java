package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.PlatformWhatsApp;
import com.platform.chat.WhatsAppConfirmService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Customer accounts made after a WhatsApp confirmation, signing up with a WhatsApp code from the platform number, and the WhatsApp number used without asking. */
class AccountsIntegrationTest extends IntegrationTestBase {
    static final String SECRET = "evolution-test-secret-0123456789";
    @Autowired JdbcClient jdbc;
    @Autowired PlatformWhatsApp platform;
    @Autowired WhatsAppConfirmService confirm;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeEvolutionApi.reset(); FakeMetaGraph.reset(); platform.disable(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String phone() { return "010" + (10_000_000L + System.nanoTime() % 80_000_000L); }

    void linkPlatformNumber() { platform.enable(); FakeEvolutionApi.STATE = "open"; platform.refresh(); }

    String lastCode() {
        var calls = FakeEvolutionApi.CALLS.stream().filter(c -> c.path().equals("/message/sendText/platform-main")).toList();
        Matcher m = Pattern.compile("(\\d{6})").matcher(calls.get(calls.size() - 1).body().get("text").toString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    String platformToken() throws Exception {
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    // ---- the platform number ---------------------------------------------------------------------------------------------------

    @Test
    void onlyThePlatformAdminLinksTheNumberThatSendsCodes() throws Exception {
        String admin = platformToken();
        Tenant shop = onboard("STORE");
        onHost("platform.test", shop.access(), "GET", "/api/v1/platform/whatsapp", null).andExpect(status().isForbidden());
        onHost("platform.test", admin, "GET", "/api/v1/platform/whatsapp", null).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.available").value(true));
        String res = ok(onHost("platform.test", admin, "POST", "/api/v1/platform/whatsapp/enable", "{}").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(res, "$.qr")).startsWith("data:image/png");
        FakeEvolutionApi.STATE = "open";
        onHost("platform.test", admin, "GET", "/api/v1/platform/whatsapp", null).andExpect(jsonPath("$.status").value("CONNECTED")).andExpect(jsonPath("$.phone").value("201012345678"));
        onHost("platform.test", admin, "POST", "/api/v1/platform/whatsapp/test", "{\"number\":\"201055550000\"}").andExpect(status().isNoContent());
        onHost("platform.test", admin, "POST", "/api/v1/platform/whatsapp/disable", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    }

    // ---- signing up with a code ------------------------------------------------------------------------------------------------

    @Test
    void signingUpNeedsTheWhatsAppCodeAndOnlyThenTheAccountExists() throws Exception {
        Tenant shop = onboard("STORE");
        String h = shop.host(), p = phone();
        String body = "{\"name\":\"Mona Hassan\",\"phone\":\"%s\",\"password\":\"Secret123x\"}".formatted(p);
        onHost(h, null, "POST", "/api/v1/auth/client/register", body).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("OTP_NOT_AVAILABLE"));   // no platform number yet
        linkPlatformNumber();
        onHost(h, null, "POST", "/api/v1/auth/client/register", "{\"name\":\"M\",\"phone\":\"%s\",\"password\":\"Secret123x\"}".formatted(p)).andExpect(status().isBadRequest());
        onHost(h, null, "POST", "/api/v1/auth/client/register", "{\"name\":\"Mona\",\"phone\":\"%s\",\"password\":\"short\"}".formatted(p)).andExpect(status().isBadRequest());
        onHost(h, null, "POST", "/api/v1/auth/client/register", body).andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(true));
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE phone = :p").param("p", "+2" + p).query(Long.class).single()).isEqualTo(0L);   // nothing exists before the code
        String code = lastCode();
        assertThat(FakeEvolutionApi.CALLS.get(FakeEvolutionApi.CALLS.size() - 1).body().get("number")).isEqualTo("2" + p);   // sent to that phone, from the platform number

        String wrong = code.equals("000000") ? "111111" : "000000";
        onHost(h, null, "POST", "/api/v1/auth/client/register/verify", "{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(p, wrong)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CODE"));
        String tokens = ok(onHost(h, null, "POST", "/api/v1/auth/client/register/verify", "{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(p, code)).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(tokens, "$.accessToken")).isNotBlank();
        onHost(h, null, "POST", "/api/v1/auth/client/register/verify", "{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(p, code)).andExpect(status().isBadRequest());   // a code works once
        onHost(h, null, "POST", "/api/v1/auth/client/register", body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS"));

        // now the phone and password sign in, here and on another shop
        Tenant other = onboard("STORE");
        for (Tenant t : List.of(shop, other)) {
            String login = ok(onHost(t.host(), null, "POST", "/api/v1/auth/client/login", "{\"phone\":\"%s\",\"password\":\"Secret123x\"}".formatted(p)).andExpect(status().isOk()));
            onHost(t.host(), JsonPath.read(login, "$.accessToken"), "GET", "/api/v1/shop/me", null).andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("+2" + p));
        }
        onHost(h, null, "POST", "/api/v1/auth/client/login", "{\"phone\":\"%s\",\"password\":\"nope-nope-1\"}".formatted(p)).andExpect(status().isUnauthorized());
    }

    @Test
    void aCodeIsLockedAfterFiveWrongTriesAndThreeCodesAnHourAreAllowed() throws Exception {
        Tenant shop = onboard("STORE");
        linkPlatformNumber();
        String p = phone();
        String body = "{\"name\":\"Mona\",\"phone\":\"%s\",\"password\":\"Secret123x\"}".formatted(p);
        onHost(shop.host(), null, "POST", "/api/v1/auth/client/register", body).andExpect(status().isOk());
        String code = lastCode();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) onHost(shop.host(), null, "POST", "/api/v1/auth/client/register/verify", "{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(p, wrong)).andExpect(status().isBadRequest());
        onHost(shop.host(), null, "POST", "/api/v1/auth/client/register/verify", "{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(p, code)).andExpect(status().isBadRequest());   // locked, even with the right code
        onHost(shop.host(), null, "POST", "/api/v1/auth/client/register", body).andExpect(status().isOk());
        onHost(shop.host(), null, "POST", "/api/v1/auth/client/register", body).andExpect(status().isOk());
        onHost(shop.host(), null, "POST", "/api/v1/auth/client/register", body).andExpect(status().isTooManyRequests());   // the fourth in an hour
    }

    @Test
    void forgettingThePasswordIsFixedWithACodeAndRevealsNothingAboutOtherPhones() throws Exception {
        Tenant shop = onboard("STORE");
        linkPlatformNumber();
        String p = phone();
        UUID acc = jdbc.sql("INSERT INTO commerce.client_accounts (name, phone, password_hash, created_via) VALUES ('Mona', :p, 'x', 'GOOGLE') RETURNING id").param("p", "+2" + p).query(UUID.class).single();
        onHost(shop.host(), null, "POST", "/api/v1/auth/client/password/forgot", "{\"phone\":\"%s\"}".formatted(phone())).andExpect(status().isOk());   // unknown phone: same answer, nothing sent
        assertThat(FakeEvolutionApi.CALLS.stream().filter(c -> c.path().contains("sendText")).count()).isEqualTo(0L);
        onHost(shop.host(), null, "POST", "/api/v1/auth/client/password/forgot", "{\"phone\":\"%s\"}".formatted(p)).andExpect(status().isOk());
        String code = lastCode();
        onHost(shop.host(), null, "POST", "/api/v1/auth/client/password/reset", "{\"phone\":\"%s\",\"code\":\"%s\",\"newPassword\":\"Brand-new-1\"}".formatted(p, code)).andExpect(status().isOk());
        onHost(shop.host(), null, "POST", "/api/v1/auth/client/login", "{\"phone\":\"%s\",\"password\":\"Brand-new-1\"}".formatted(p)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT must_change_password FROM commerce.client_accounts WHERE id = :i").param("i", acc).query(Boolean.class).single()).isFalse();
    }

    // ---- accounts after a confirmation on WhatsApp -----------------------------------------------------------------------------

    record Shop(Tenant t, UUID id, String variant, String instance) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","brand":"Acme","description":"Nice","variants":[{"sku":"S-%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(uniq(), uniq(), branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        return new Shop(t, id, JsonPath.read(p, "$.variants[0].id"), jdbc.sql("SELECT instance FROM chat.evolution_settings WHERE tenant_id = :t").param("t", id).query(String.class).single());
    }

    void whatsappSays(Shop s, String from, String text) throws Exception {
        String raw = "{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"pushName\":\"Mona\",\"key\":{\"remoteJid\":\"2%s@s.whatsapp.net\",\"fromMe\":false,\"id\":\"W%s\"},\"message\":{\"conversation\":\"%s\"}}}".formatted(s.instance(), from, uniq(), text);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/chat/evolution/webhook/" + SECRET).header("Host", "platform.test").contentType("application/json").content(raw)).andExpect(status().isOk());
    }

    String lastTextTo(Shop s, String number) {
        var texts = FakeEvolutionApi.CALLS.stream().filter(c -> c.path().equals("/message/sendText/" + s.instance()) && number.equals(c.body().get("number"))).map(c -> c.body().get("text").toString()).toList();
        return String.join("\n---\n", texts);
    }

    Map<String, Object> order(Shop s, boolean withPhone) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(Map.of("items", List.of(Map.of("variantId", s.variant(), "quantity", 1)), "customerName", "Mona Hassan", "governorateCode", "CAI", "addressLine1", "5 Tahrir St, floor 2", "area", "Dokki", "paymentMethod", "CASH_ON_DELIVERY"));
        if (withPhone) m.put("phone", "01099998888");
        return m;
    }

    @Test
    void chattingOnWhatsAppUsesThatNumberWithoutAskingAndTheConfirmationCreatesTheAccount() throws Exception {
        Shop s = shop();
        String p = phone();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(s, false)));   // the model does not even pass a phone
        FakeAiProvider.SCRIPT.add(turns -> com.platform.chat.AiProvider.Turn.model("هل تؤكد الطلب؟"));
        whatsappSays(s, p, "عايزة اطلب قميص");
        assertThat(FakeAiProvider.lastSystem).contains("CHATTING WITH YOU ON WHATSAPP").contains("NEVER ask for a phone number");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE phone = :p").param("p", "+2" + p).query(Long.class).single()).isEqualTo(0L);   // nothing yet: the order is not placed

        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("place_order", Map.of()));
        whatsappSays(s, p, "تمام");
        var order = jdbc.sql("SELECT customer_phone_snapshot, whatsapp_number, whatsapp_confirmed_at, user_id FROM commerce.orders WHERE tenant_id = :t").param("t", s.id()).query().listOfRows().get(0);
        assertThat(order.get("customer_phone_snapshot")).isEqualTo("+2" + p);   // the WhatsApp number was used as the phone
        assertThat(order.get("whatsapp_number")).isEqualTo("+2" + p);
        assertThat(order.get("whatsapp_confirmed_at")).isNotNull();
        String said = lastTextTo(s, "2" + p);
        assertThat(said).contains("تم تأكيد الأوردر").contains("رقم الدخول: " + p).contains("كلمة المرور المؤقتة: ").contains("/login").doesNotContain("{");
        Matcher pw = Pattern.compile("كلمة المرور المؤقتة: (\\S+)").matcher(said);
        assertThat(pw.find()).isTrue();
        assertThat(order.get("user_id")).isNotNull();   // the order is attached to the new account

        // the credentials really work, on this shop and on any other
        onHost(s.t().host(), null, "POST", "/api/v1/auth/client/login", "{\"phone\":\"%s\",\"password\":\"%s\"}".formatted(p, pw.group(1))).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT must_change_password FROM commerce.client_accounts WHERE phone = :p").param("p", "+2" + p).query(Boolean.class).single()).isTrue();

        // a second order, here or in another shop, creates no second account and says where to follow it
        Shop other = shop();
        FakeEvolutionApi.CALLS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(other, false)));
        FakeAiProvider.SCRIPT.add(turns -> com.platform.chat.AiProvider.Turn.model("هل تؤكدي الطلب؟"));
        whatsappSays(other, p, "عايزة قميص تاني");
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("place_order", Map.of()));
        whatsappSays(other, p, "تمام");
        String again = lastTextTo(other, "2" + p);
        assertThat(again).contains("عندك حساب بالفعل").contains("/account").doesNotContain("كلمة المرور المؤقتة");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE phone = :p").param("p", "+2" + p).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND user_id IS NOT NULL").param("t", other.id()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void anOrderRequestedOnMessengerCreatesNoAccountUntilItIsConfirmedOnWhatsApp() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String p = phone();
        // the order is placed from a website chat instead (no WhatsApp), which is the same situation: requested, not confirmed
        String conv = UUID.randomUUID().toString();
        Map<String, Object> args = order(s, true);
        args.put("phone", p);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", args));
        FakeAiProvider.SCRIPT.add(turns -> com.platform.chat.AiProvider.Turn.model("هل تؤكد؟"));
        onHost(h, null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"ok\"}".formatted(conv)).andExpect(status().isOk());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("place_order", Map.of()));
        onHost(h, null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"yes\"}".formatted(conv)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE phone = :p").param("p", "+2" + p).query(Long.class).single()).isEqualTo(0L);   // not confirmed on WhatsApp: no account

        // the customer confirms on WhatsApp later: now the account is made and told, once
        String number = jdbc.sql("SELECT order_number FROM commerce.orders WHERE tenant_id = :t").param("t", s.id()).query(String.class).single();
        String reply = confirm.confirmNow(s.id(), "2" + p, number, true);
        assertThat(reply).contains("تم تأكيد طلبك").contains("رقم الدخول: " + p).contains("كلمة المرور المؤقتة");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE phone = :p").param("p", "+2" + p).query(Long.class).single()).isEqualTo(1L);
        assertThat(confirm.confirmNow(s.id(), "2" + p, number, true)).doesNotContain("كلمة المرور المؤقتة");   // asking again does not repeat it
    }
}
