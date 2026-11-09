package com.platform.chat.unofficial;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Messenger and Instagram transports against a fake sidecar (a local HTTP server): typed outcomes, headers, never any credential in an outcome. No real account involved. */
class FbBridgeSenderTest {
    static final ObjectMapper JSON = new ObjectMapper();
    HttpServer server;
    String base;
    final List<String> requests = new CopyOnWriteArrayList<>();   // "METHOD path secret body"
    volatile int status = 200;
    volatile String reply = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + ex.getRequestHeaders().getFirst("X-Bridge-Secret") + " " + body);
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    FbBridgeSender fb() { return new FbBridgeSender(base, "s3cret"); }

    JsonNode lastBody() throws Exception {
        String r = requests.get(requests.size() - 1);
        return JSON.readTree(r.substring(r.indexOf('{')));
    }

    @Test
    void handlesOnlyFacebook_andIsSupported() {
        assertThat(fb().handles("FACEBOOK")).isTrue();
        assertThat(fb().handles("INSTAGRAM")).isFalse();
        assertThat(fb().supported()).isTrue();
        assertThat(new FbBridgeSender("", "").healthy()).isFalse();   // not configured: unreachable, never an exception
    }

    @Test
    void sendMapsEveryOutcome_andSendsTheContractBody() throws Exception {
        UUID t = UUID.randomUUID();
        reply = "{\"result\":\"SENT\"}";
        assertThat(fb().send(t, "FACEBOOK", "100012345", "hello", 2500).ok()).isTrue();
        assertThat(requests.get(0)).startsWith("POST /messages/send s3cret ");
        JsonNode b = lastBody();
        assertThat(b.path("tenant").asText()).isEqualTo(t.toString());
        assertThat(b.path("recipient").asText()).isEqualTo("100012345");
        assertThat(b.path("text").asText()).isEqualTo("hello");
        assertThat(b.path("typing_ms").asInt()).isEqualTo(2500);

        record Case(String reply, OutOfWindowSender.Kind kind) {}
        for (Case c : List.of(new Case("{\"result\":\"BLOCKED\",\"detail\":\"checkpoint\"}", OutOfWindowSender.Kind.BLOCKED), new Case("{\"result\":\"SESSION_EXPIRED\"}", OutOfWindowSender.Kind.SESSION_EXPIRED),
                new Case("{\"result\":\"NOT_FOUND\"}", OutOfWindowSender.Kind.NOT_FOUND), new Case("{\"result\":\"FAILED\",\"detail\":\"boom\"}", OutOfWindowSender.Kind.FAILED), new Case("{\"result\":\"???\"}", OutOfWindowSender.Kind.FAILED))) {
            reply = c.reply();
            assertThat(fb().send(t, "FACEBOOK", "1", "x").kind()).as(c.reply()).isEqualTo(c.kind());
        }
        reply = "{\"result\":\"BLOCKED\",\"detail\":\"checkpoint\"}";
        assertThat(fb().send(t, "FACEBOOK", "1", "x").detail()).isEqualTo("checkpoint");
        status = 401;
        reply = "{}";
        assertThat(fb().send(t, "FACEBOOK", "1", "x").detail()).isEqualTo("BRIDGE_AUTH");
        assertThat(fb().send(t, "INSTAGRAM", "1", "x").detail()).isEqualTo("UNSUPPORTED");
        int port = server.getAddress().getPort();
        server.stop(0);
        assertThat(new FbBridgeSender("http://127.0.0.1:" + port, "s3cret").send(t, "FACEBOOK", "1", "x").detail()).isEqualTo("BRIDGE_UNREACHABLE");
        start();   // for @AfterEach
    }

    @Test
    void healthAndStatus() {
        UUID t = UUID.randomUUID();
        reply = "{\"ok\":true}";
        assertThat(fb().healthy()).isTrue();
        reply = "{\"exists\":true,\"logged_in\":true,\"account\":\"My Page\"}";
        var st = fb().status(t, "FACEBOOK");
        assertThat(st.active()).isTrue();
        assertThat(st.accountName()).isEqualTo("My Page");
        assertThat(requests.get(requests.size() - 1)).startsWith("GET /sessions/" + t + "/status ");
        reply = "{\"exists\":true,\"logged_in\":false}";
        assertThat(fb().status(t, "FACEBOOK").state()).isEqualTo("NEEDS_LOGIN");
        reply = "{\"exists\":false,\"logged_in\":false}";
        assertThat(fb().status(t, "FACEBOOK").state()).isEqualTo("NONE");
        status = 500;
        assertThat(fb().healthy()).isFalse();
    }

    @Test
    void cookieLogin_sendsCookiesAndPageId_mapsErrors_neverEchoesTheCookies() throws Exception {
        UUID t = UUID.randomUUID();
        String cookies = "c_user=1; xs=SECRET-COOKIE-VALUE-123";
        reply = "{\"ok\":true,\"account\":\"Shop Page\"}";
        var ok = fb().loginWithCookies(t, "FACEBOOK", cookies, "555000111");
        assertThat(ok.ok()).isTrue();
        assertThat(ok.accountName()).isEqualTo("Shop Page");
        JsonNode b = lastBody();
        assertThat(b.path("cookies").asText()).isEqualTo(cookies);
        assertThat(b.path("page_id").asText()).isEqualTo("555000111");
        assertThat(requests.get(0)).startsWith("POST /sessions/login s3cret ");
        for (String[] c : new String[][] {{"BAD_CREDENTIALS", "BAD_CREDENTIALS"}, {"CHECKPOINT", "CHALLENGE"}, {"FAILED", "FAILED"}, {"WHATEVER", "FAILED"}}) {
            reply = "{\"ok\":false,\"error\":\"" + c[0] + "\"}";
            var r = fb().loginWithCookies(t, "FACEBOOK", cookies, "555000111");
            assertThat(r.ok()).isFalse();
            assertThat(r.error()).isEqualTo(c[1]);
            assertThat(r.toString()).doesNotContain("SECRET-COOKIE");
        }
        assertThat(new FbBridgeSender("", "").loginWithCookies(t, "FACEBOOK", cookies, "1").error()).isEqualTo("UNREACHABLE");
        assertThat(fb().login(t, "FACEBOOK", "u", "p", null).error()).isEqualTo("UNSUPPORTED");   // password login is not how Messenger connects
    }

    @Test
    void logoutIsIdempotentAndFiresOneCall() {
        UUID t = UUID.randomUUID();
        fb().logout(t, "FACEBOOK");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).startsWith("POST /sessions/logout s3cret ").contains(t.toString());
        fb().logout(t, "INSTAGRAM");   // not this sender's platform: nothing sent
        assertThat(requests).hasSize(1);
    }

    @Test
    void igBridgeClientParsesFollowersMediaAndComments() {
        IgBridgeClient ig = new IgBridgeClient(base, "s3cret");
        UUID t = UUID.randomUUID();
        reply = "{\"ok\":true,\"users\":[{\"pk\":\"11\",\"username\":\"ann\",\"full_name\":\"Ann A\",\"is_private\":true},{\"pk\":\"\",\"username\":\"skip\"}],\"next_cursor\":\"abc\"}";
        var f = ig.followers(t, 100, null);
        assertThat(f.ok()).isTrue();
        assertThat(f.users()).hasSize(1);
        assertThat(f.users().get(0).isPrivate()).isTrue();
        assertThat(f.nextCursor()).isEqualTo("abc");
        assertThat(requests.get(requests.size() - 1)).startsWith("GET /instagram/" + t + "/followers s3cret ");
        reply = "{\"ok\":true,\"users\":[],\"next_cursor\":null}";
        assertThat(ig.followers(t, 100, "abc").nextCursor()).isNull();
        reply = "{\"ok\":true,\"media\":[{\"id\":\"9_1\",\"code\":\"CODE\",\"caption\":\"c\",\"comment_count\":3}]}";
        assertThat(ig.recentMedia(t, 12).media().get(0).commentCount()).isEqualTo(3);
        reply = "{\"ok\":true,\"comments\":[{\"pk\":\"c1\",\"text\":\"nice\",\"user\":{\"pk\":\"22\",\"username\":\"bob\",\"full_name\":\"Bob\",\"is_private\":false}}]}";
        var c = ig.comments(t, "9_1", 100);
        assertThat(c.comments().get(0).user().username()).isEqualTo("bob");
        for (String e : new String[] {"SESSION_EXPIRED", "CHALLENGE", "RATE_LIMITED"}) {
            reply = "{\"ok\":false,\"error\":\"" + e + "\"}";
            assertThat(ig.followers(t, 10, null).error()).isEqualTo(e);
        }
        reply = "{\"ok\":false,\"error\":\"odd\"}";
        assertThat(ig.followers(t, 10, null).error()).isEqualTo("FAILED");
        assertThat(new IgBridgeClient("", "").followers(t, 10, null).error()).isEqualTo("UNREACHABLE");
        List<String> none = new ArrayList<>();
        assertThat(none).isEmpty();
    }
}
