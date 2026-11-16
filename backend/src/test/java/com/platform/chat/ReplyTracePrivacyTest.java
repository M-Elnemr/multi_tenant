package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a stored trace can and cannot contain, tried with realistic sensitive values: Egyptian mobile numbers (plain, +20, separated, as numbers), shop and chat ids, tokens, a customer's sentence, an
 * exception message that quotes a request. Every attribute producer goes through the allowlist in {@link ReplyTrace}; these tests push the sensitive values through each kind of attribute.
 */
class ReplyTracePrivacyTest {
    static final List<String> PHONES = List.of("01012345678", "+201012345678", "201012345678", "0101-234-5678", "010 1234 5678", "(010) 1234-5678", "+20 10 1234 5678", "٠١٠١٢٣٤٥٦٧٨", "1012345678", "0235678901");
    static final Pattern UUID_RX = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    final List<ReplyTrace> done = new CopyOnWriteArrayList<>();
    java.util.function.Consumer<ReplyTrace> saved;

    @BeforeEach
    void install() { saved = ReplyTrace.sink; ReplyTrace.sink = done::add; ReplyTrace.enabled = true; }

    @AfterEach
    void restore() { ReplyTrace.sink = saved; }

    @Test
    void aPhoneNumberInAnyShapeCannotBeStoredAsAStringAttribute() {
        UUID tenant = UUID.randomUUID(), conv = UUID.randomUUID();
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(tenant, conv, false, false);
        for (String phone : PHONES) {
            // through every string-typed attribute that a producer could ever misuse
            ReplyTrace.instant("provider_attempt_hedged", "provider", phone, "after_ms", 1);
            ReplyTrace.instant("provider_call", "model", phone, "provider", phone, "failure", phone, "outcome", phone, "role", phone, "purpose", phone);
            ReplyTrace.instant("fallback_started", "from", phone, "to", phone, "category", phone);
            ReplyTrace.instant("rule_checks", "kinds", phone, "reason", phone);
            ReplyTrace.instant("review", "verdict", phone, "detail", phone, "model", phone);
            ReplyTrace.instant("outbound_failed", "channel", phone, "via", phone, "kind", phone, "status", phone, "error", phone);
            ReplyTrace.instant("tool", "tool", phone);
            ReplyTrace.instant("phase", "name", phone);
            // and as a number, in every numeric attribute
            try {
                long n = Long.parseLong(phone.replaceAll("\\D", ""));
                ReplyTrace.instant("provider_call", "lines", n, "call", n, "attempts", n, "in", n, "out", n, "cached", n, "after_ms", n, "waited_ms", n, "deterministic_ms", n, "chars", n, "facts", n, "tools", n);
            } catch (NumberFormatException arabicDigits) { /* not ASCII digits: only the string path applies */ }
        }
        ReplyTrace.finish(false, null);
        String stored = stored(t);
        for (String phone : PHONES) assertThat(stored).as(phone).doesNotContain(phone);
        assertThat(stored).doesNotContain("1012345678").doesNotContain("235678901").doesNotContainPattern("\\d{7,}");
        assertThat(stored).doesNotContain(tenant.toString()).doesNotContain(conv.toString()).doesNotContainPattern(UUID_RX.pattern());
    }

    @Test
    void sensitiveWordsAndSentencesAreNeverStoredWhateverTheAttribute() {
        ReplyTrace t = ReplyTrace.begin("WHATSAPP");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        String[] secrets = {"Bearer sk-live-4eC39HqLyjWDarjtT1zdp7dc", "api_key=AKIA0123456789ABCDEF", "ahmed@example.com", "عايز بنطلون جينز مقاس 44", "شارع التحرير 12 الدقي", "ORD-2026-000123", "Cookie: session=abc123def"};
        for (String sec : secrets) {
            for (String name : List.of("model", "provider", "from", "to", "failure", "category", "error", "detail", "kinds", "tool", "verdict", "role", "purpose", "which", "reason", "name", "channel", "via", "kind", "status", "outcome"))
                ReplyTrace.instant("provider_call", name, sec);
            ReplyTrace.instant("note", sec, true);
            ReplyTrace.note(sec, true);
        }
        ReplyTrace.finish(false, null);
        String stored = stored(t);
        for (String sec : secrets) assertThat(stored).as(sec).doesNotContain(sec);
        assertThat(stored).doesNotContain("Bearer").doesNotContain("sk-live").doesNotContain("AKIA").doesNotContain("@").doesNotContain("ORD-").doesNotContain("Cookie").doesNotContain("بنطلون").doesNotContain("التحرير");
    }

    @Test
    void anExceptionsMessageNeverReachesATraceOnlyItsClassName() {
        ReplyTrace t = ReplyTrace.begin("WHATSAPP");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        try {
            ReplyTrace.outbound("WHATSAPP", "cloud", "text", () -> { throw new IllegalStateException("POST /messages to=201012345678 body=\"عايز بنطلون\" token=EAAG-secret failed"); });
        } catch (IllegalStateException expected) { /* the channel handles it */ }
        ReplyTrace.finish(false, null);
        ReplyTrace.completeDeferred();
        String stored = stored(t);
        assertThat(stored).contains("IllegalStateException").doesNotContain("201012345678").doesNotContain("EAAG").doesNotContain("بنطلون").doesNotContain("POST");
    }

    @Test
    void onlyApprovedAttributeNamesAndTypesSurvive() {
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        ReplyTrace.instant("provider_call", "lines", "five", "usable", "yes", "in", 12.5, "out", -3, "call", 10_001, "tenant", "x", "conversation_id", "y", "message", "hello", "phone", "01012345678", "order_id", "A-1");
        ReplyTrace.finish(false, null);
        var event = t.snapshot().stream().filter(e -> e.name().equals("provider_call")).findFirst().orElseThrow();
        assertThat(event.a()).isEmpty();   // a string where a number belongs, a string where a flag belongs, a fraction, a negative, a number over its cap, and every name nobody approved
    }

    @Test
    void theSummaryAndTheColumnsAreAsCleanAsTheEvents() throws Exception {
        UUID tenant = UUID.randomUUID(), conv = UUID.randomUUID();
        ReplyTrace t = ReplyTrace.begin("not a channel 01012345678");
        ReplyTrace.identify(tenant, conv, false, false);
        ReplyTrace.Call call = ReplyTrace.callStart("glm", "glm-5");
        call.attempt(false).finish(200, null, false, true, new AiProvider.Usage(2000, 100, 1500));
        call.end(true, null, new AiProvider.Usage(2000, 100, 1500));
        ReplyTrace.finish(false, null);
        String json = new ObjectMapper().writeValueAsString(t.summary());
        assertThat(t.channel).isEqualTo("other");   // the channel column is one of the known channels
        assertThat(t.model()).matches("[a-z0-9_]{1,12}");
        assertThat(json).doesNotContain(tenant.toString()).doesNotContain(conv.toString()).doesNotContainPattern(UUID_RX.pattern());
    }

    static String stored(ReplyTrace t) {
        List<String> parts = new ArrayList<>();
        t.snapshot().forEach(e -> parts.add(e.name() + e.a()));
        return String.join("\n", parts) + t.summary() + t.channel + t.model() + t.source();
    }
}
