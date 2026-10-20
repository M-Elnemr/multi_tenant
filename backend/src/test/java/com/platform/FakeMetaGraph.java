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

    public static synchronized void reset() { SENT.clear(); }

    @Override
    public synchronized Map<String, Object> get(String path, String token) {
        if (token.startsWith("BAD")) throw new MetaException("Invalid OAuth access token");
        return Map.of("id", path.split("\\?")[0], "name", "Test Page");
    }

    @Override
    public synchronized Map<String, Object> post(String path, String token, Map<String, Object> body) {
        SENT.add(new Sent(path, token, body));
        return Map.of("ok", true);
    }
}
