package com.platform;

import com.platform.chat.EvolutionApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for the self-hosted Evolution API in tests: records every call; the phone counts as linked once the test says so. */
@Component
@Primary
public class FakeEvolutionApi implements EvolutionApi {
    public record Call(String method, String path, Map<String, Object> body) {}

    public static final List<Call> CALLS = new ArrayList<>();
    public static volatile String STATE = "close";
    public static volatile boolean FAIL_SEND = false;

    public static synchronized void reset() { CALLS.clear(); STATE = "close"; FAIL_SEND = false; }

    @Override
    public synchronized Map<String, Object> call(String method, String path, Map<String, Object> body) {
        CALLS.add(new Call(method, path, body));
        if (path.startsWith("/instance/create")) return Map.of("instance", Map.of("instanceName", String.valueOf(body.get("instanceName"))), "qrcode", Map.of("base64", "data:image/png;base64,QR"));
        if (path.startsWith("/instance/connectionState")) return Map.of("instance", Map.of("state", STATE));
        if (path.startsWith("/instance/connect/")) return Map.of("base64", "data:image/png;base64,QRCODE", "pairingCode", "ABCD1234");
        if (path.startsWith("/instance/fetchInstances")) return Map.of("data", List.of(Map.of("ownerJid", "201012345678@s.whatsapp.net")));
        if (path.startsWith("/message/sendText/")) { if (FAIL_SEND) throw new EvolutionException("send failed", 500); return Map.of("key", Map.of("id", "OUT1")); }
        return Map.of();
    }
}
