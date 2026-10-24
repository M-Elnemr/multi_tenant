package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.EvolutionService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The optional unofficial WhatsApp mode: off until the owner accepts the warning, QR linking, AI answers and free confirmations on the shop's own number, and switching it off again. */
class EvolutionIntegrationTest extends IntegrationTestBase {
    static final String SECRET = "evolution-test-secret-0123456789";
    @Autowired JdbcClient jdbc;
    @Autowired EvolutionService evolution;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeEvolutionApi.reset(); FakeMetaGraph.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    ResultActions webhook(String secret, String raw) throws Exception {
        return mvc.perform(post("/api/v1/chat/evolution/webhook/" + secret).header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(raw));
    }

    static String message(String instance, String from, String id, String text) {
        return "{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"key\":{\"remoteJid\":\"%s@s.whatsapp.net\",\"fromMe\":false,\"id\":\"%s\"},\"message\":{\"conversation\":\"%s\"}}}".formatted(instance, from, id, text);
    }

    String instanceOf(Tenant t) {
        return jdbc.sql("SELECT instance FROM chat.evolution_settings es JOIN core.tenants c ON c.id = es.tenant_id WHERE c.slug = :s").param("s", t.slug()).query(String.class).single();
    }

    UUID idOf(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    @Test
    void theModeIsOffUntilTheOwnerAcceptsTheWarningThenLinksThePhoneAndCanSwitchItOff() throws Exception {
        Tenant t = onboard("STORE");
        String h = t.host(), owner = t.access();
        onHost(h, owner, "GET", "/api/v1/store/ai/evolution", null).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.status").value("DISCONNECTED"));
        onHost(h, owner, "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":false}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RISK_NOT_ACCEPTED"));
        onHost(h, owner, "POST", "/api/v1/store/ai/evolution/enable", "{}").andExpect(status().isBadRequest());
        assertThat(FakeEvolutionApi.CALLS).isEmpty();   // nothing is created without the warning being accepted

        String res = ok(onHost(h, owner, "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(res, "$.qr")).startsWith("data:image/png");
        assertThat((String) JsonPath.read(res, "$.status")).isEqualTo("DISCONNECTED");
        var create = FakeEvolutionApi.CALLS.stream().filter(c -> c.path().equals("/instance/create")).findFirst().orElseThrow();
        assertThat(create.body().get("integration")).isEqualTo("WHATSAPP-BAILEYS");
        assertThat(create.body().get("groupsIgnore")).isEqualTo(true);
        assertThat(create.body().toString()).contains("/api/v1/chat/evolution/webhook/" + SECRET);
        assertThat(evolution.connected(idOf(t))).isFalse();

        // the owner scanned the QR: the next check says the phone is linked
        FakeEvolutionApi.STATE = "open";
        onHost(h, owner, "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED")).andExpect(jsonPath("$.phone").value("201012345678"));
        assertThat(evolution.connected(idOf(t))).isTrue();
        onHost(h, owner, "POST", "/api/v1/store/ai/evolution/test", "{\"number\":\"+20 101 111 2222\"}").andExpect(status().isNoContent());
        var sent = FakeEvolutionApi.CALLS.get(FakeEvolutionApi.CALLS.size() - 1);
        assertThat(sent.path()).isEqualTo("/message/sendText/" + instanceOf(t));
        assertThat(sent.body().get("number")).isEqualTo("201011112222");
        onHost(h, owner, "POST", "/api/v1/store/ai/evolution/test", "{\"number\":\"12\"}").andExpect(status().isBadRequest());

        // switching it off unlinks the phone and removes the instance; nothing is sent afterwards
        onHost(h, owner, "POST", "/api/v1/store/ai/evolution/disable", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.status").value("DISCONNECTED"));
        assertThat(FakeEvolutionApi.CALLS.stream().map(c -> c.method() + " " + c.path()).toList()).contains("DELETE /instance/logout/" + instanceOf(t), "DELETE /instance/delete/" + instanceOf(t));
        assertThat(evolution.connected(idOf(t))).isFalse();
        int calls = FakeEvolutionApi.CALLS.size();
        assertThat(evolution.sendFree(idOf(t), "201011112222", "hello")).isFalse();
        assertThat(FakeEvolutionApi.CALLS.size()).isEqualTo(calls);
        onHost(h, owner, "POST", "/api/v1/store/ai/evolution/test", "{\"number\":\"201011112222\"}").andExpect(status().isConflict());
        onHost(h, null, "GET", "/api/v1/store/ai/evolution", null).andExpect(status().isUnauthorized());
    }

    @Test
    void theAssistantAnswersOnTheShopsOwnNumberAndIgnoresGroupsEchoesAndRepeats() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
        String inst = instanceOf(t);

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Welcome! What are you looking for?"));
        String id = "MSG" + uniq();
        webhook(SECRET, message(inst, "201055550000", id, "hello")).andExpect(status().isOk());
        var sent = FakeEvolutionApi.CALLS.get(FakeEvolutionApi.CALLS.size() - 1);
        assertThat(sent.path()).isEqualTo("/message/sendText/" + inst);
        assertThat(sent.body().get("number")).isEqualTo("201055550000");
        assertThat((String) sent.body().get("text")).contains("Welcome!");

        int count = FakeEvolutionApi.CALLS.size();
        webhook(SECRET, message(inst, "201055550000", id, "hello")).andExpect(status().isOk());   // the same delivery again
        webhook(SECRET, "{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"key\":{\"remoteJid\":\"201055550000@s.whatsapp.net\",\"fromMe\":true,\"id\":\"E%s\"},\"message\":{\"conversation\":\"x\"}}}".formatted(inst, uniq())).andExpect(status().isOk());   // our own message
        webhook(SECRET, "{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"key\":{\"remoteJid\":\"1203630@g.us\",\"fromMe\":false,\"id\":\"G%s\"},\"message\":{\"conversation\":\"hi all\"}}}".formatted(inst, uniq())).andExpect(status().isOk());   // a group
        webhook(SECRET, message("shop-unknown", "201055550000", "U" + uniq(), "hello")).andExpect(status().isOk());   // an unknown instance
        assertThat(FakeEvolutionApi.CALLS.size()).isEqualTo(count);

        // a wrong secret is refused and nothing is handled
        webhook("wrong-secret-wrong-secret", message(inst, "201055550000", "W" + uniq(), "hello")).andExpect(status().isForbidden());
        assertThat(FakeEvolutionApi.CALLS.size()).isEqualTo(count);

        // connection updates keep the state in step
        webhook(SECRET, "{\"event\":\"connection.update\",\"instance\":\"%s\",\"data\":{\"state\":\"close\"}}".formatted(inst)).andExpect(status().isOk());
        assertThat(evolution.connected(idOf(t))).isFalse();
    }

    @Test
    void whenTheOwnNumberIsLinkedOrderConfirmationsAndCartRemindersGoOutAsFreeMessages() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
        UUID id = idOf(t);
        assertThat(evolution.sendFree(id, "201011112222", "your cart is waiting")).isTrue();
        FakeEvolutionApi.FAIL_SEND = true;
        assertThat(evolution.sendFree(id, "201011112222", "again")).isFalse();   // a failed send reports false and never throws
    }
}
