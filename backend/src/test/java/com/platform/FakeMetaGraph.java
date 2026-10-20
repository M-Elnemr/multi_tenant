package com.platform;

import com.platform.chat.MetaGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for Meta's Graph API in tests: any token starting with "BAD" is rejected; every POST is recorded. */
@Component
@Primary
public class FakeMetaGraph implements MetaGraph {
    public record Sent(String path, String token, Map<String, Object> body) {}

    public static final List<Sent> SENT = new ArrayList<>();

    /** What "Facebook login" finds for the person (set by the test). */
    public static volatile String PAGE_ID = "111111111111", IG_ID = "222222222222";

    /** What Meta says about the message templates, and whether sending a template fails. */
    public static volatile String TEMPLATE_STATUS = "APPROVED";
    public static volatile boolean FAIL_TEMPLATE_SEND = false;

    public static synchronized void reset() { SENT.clear(); TEMPLATE_STATUS = "APPROVED"; FAIL_TEMPLATE_SEND = false; }

    @Override
    public synchronized Map<String, Object> get(String path, String token) {
        if (path.startsWith("oauth/access_token")) {
            if (path.contains("badcode")) throw new MetaException("Invalid verification code");
            return Map.of("access_token", path.contains("fb_exchange_token") ? "LONG-USER-TOKEN-0123456789" : "SHORT-USER-TOKEN-0123456789");
        }
        if (path.startsWith("me/accounts"))
            return Map.of("data", java.util.List.of(Map.of("id", PAGE_ID, "name", "Shop Page", "access_token", "PAGE-TOKEN-0123456789012", "instagram_business_account", Map.of("id", IG_ID, "username", "shop_ig"))));
        if (path.startsWith("me/businesses")) return Map.of("data", java.util.List.of());
        if (path.contains("/message_templates")) return Map.of("data", java.util.List.of(Map.of("name", "t", "status", TEMPLATE_STATUS)));
        if (token.startsWith("BAD")) throw new MetaException("Invalid OAuth access token");
        return Map.of("id", path.split("\\?")[0], "name", "Test Page");
    }

    @Override
    public synchronized Map<String, Object> post(String path, String token, Map<String, Object> body) {
        if (path.endsWith("/message_templates")) return Map.of("id", "tpl");
        if ("template".equals(body.get("type")) && FAIL_TEMPLATE_SEND) throw new MetaException("Template send failed");
        SENT.add(new Sent(path, token, body));
        return Map.of("ok", true, "messages", java.util.List.of(Map.of("id", "wamid.TEST")));
    }
}
