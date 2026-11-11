package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the assistant asks for at checkout (production chat, 2026-11-11): the governorate and the area are a complete delivery address (the street and the building are optional and a refusal is
 * final), a first name is enough, the number is asked for as the WhatsApp number and never again once given.
 */
class CheckoutAskingIntegrationTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    record Shop(UUID id, String name, String variant) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        String name = "بنطلون جينز سليم " + uniq();
        String made = onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"%s\",\"variants\":[{\"sku\":\"J-%s\",\"priceMinor\":80000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(name, uniq(), branch))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Shop(jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), name, JsonPath.read(made, "$.variants[0].id"));
    }

    String say(Shop s, String ext, String text) { return chat.reply(s.id(), "WEB", ext, null, text).text(); }

    Map<String, Object> order(Shop s, Map<String, Object> extra) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(Map.of("items", List.of(Map.of("variantId", s.variant(), "quantity", 1)), "customerName", "محمود", "phone", "01120224441", "governorateCode", "SHR", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0));
        m.putAll(extra);
        return m;
    }

    @Test
    void theGovernorateAndTheAreaAreACompleteAddressAndTheStreetIsNeverRequired() throws Exception {
        Shop s = shop();
        String ext = "ck-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم، محتاج منك اسمك الأول ورقم الواتساب والمحافظة والمنطقة"));
        say(s, ext, "عايز " + s.name());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا أستاذ محمود، محتاج المحافظة والمنطقة"));
        say(s, ext, "محمود 01120224441");   // the number: the WhatsApp number from now on
        // «الزقازيق القومية», and the customer will not give a street: the order is prepared with the governorate and the area alone
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(s, Map.of("area", "القومية"))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        String reply = say(s, ext, "الزقازيق القوميه، مش هقول اسم الشارع");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
        assertThat(reply).contains("ملخص").contains("القومية");
        assertThat(jdbc.sql("SELECT pending ->> 'phone' FROM chat.conversations WHERE external_id = :c AND channel = 'WEB'").param("c", ext).query(String.class).single()).isEqualTo("01120224441");
    }

    @Test
    void whenNoAddressAtAllIsGivenTheModelIsToldToAskOnlyForTheGovernorateAndTheArea() throws Exception {
        Shop s = shop();
        String ext = "ck2-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        say(s, ext, "عايز " + s.name() + " 01120224441");
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(s, Map.of())));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("محتاج المحافظة والمنطقة"));
        say(s, ext, "ايوه جهزه");
        String error = String.valueOf(FakeAiProvider.RESULTS.get(0).result().get("error"));
        assertThat(error).contains("governorate and the area").contains("never insist").doesNotContain("street, building, apartment");
    }

    @Test
    void theNameAndNumberErrorsAskForTheFirstNameAndTheWhatsAppNumber() throws Exception {
        Shop s = shop();
        String ext = "ck3-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        say(s, ext, "عايز " + s.name());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(s, Map.of("customerName", "", "area", "القومية"))));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(s, Map.of("phone", "", "area", "القومية"))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("محتاج اسمك الأول ورقم الواتساب"));
        say(s, ext, "جهزه");
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(0).result().get("error"))).contains("first name").contains("not needed");
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(1).result().get("error"))).contains("WhatsApp number").contains("never a plain mobile number");
    }
}
