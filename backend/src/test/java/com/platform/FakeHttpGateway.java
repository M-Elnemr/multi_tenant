package com.platform;

import com.platform.integrations.HttpGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for every platform's API in tests: answers are registered per method + URL fragment (first match wins; a rule may be used once), and every call is recorded. */
@Component
@Primary
public class FakeHttpGateway implements HttpGateway {
    public record Call(String method, String url, Map<String, String> headers, String body) {}

    private static final class Rule {
        final String method, urlPart, body;
        final int status;
        final Map<String, List<String>> headers;
        final boolean once;
        boolean used;
        Rule(String method, String urlPart, int status, String body, Map<String, List<String>> headers, boolean once) { this.method = method; this.urlPart = urlPart; this.status = status; this.body = body; this.headers = headers; this.once = once; }
    }

    public static final List<Call> CALLS = new ArrayList<>();
    private static final List<Rule> RULES = new ArrayList<>();

    public static synchronized void reset() { CALLS.clear(); RULES.clear(); }

    public static synchronized void when(String method, String urlPart, int status, String body) { RULES.add(new Rule(method, urlPart, status, body, Map.of(), false)); }

    public static synchronized void once(String method, String urlPart, int status, String body) { RULES.add(new Rule(method, urlPart, status, body, Map.of(), true)); }

    public static synchronized void when(String method, String urlPart, int status, String body, Map<String, List<String>> headers) { RULES.add(new Rule(method, urlPart, status, body, headers, false)); }

    public static synchronized List<Call> calls(String method, String urlPart) { return CALLS.stream().filter((c) -> c.method().equals(method) && c.url().contains(urlPart)).toList(); }

    @Override
    public synchronized Res send(Req r) {
        CALLS.add(new Call(r.method(), r.url(), r.headers(), r.body()));
        for (Rule rule : RULES) {
            if (rule.once && rule.used) continue;
            if (rule.method.equals(r.method()) && r.url().contains(rule.urlPart)) { rule.used = true; return new Res(rule.status, rule.body, rule.headers); }
        }
        return new Res(404, "{\"message\":\"no fake answer for " + r.method() + " " + r.url() + "\"}", Map.of());
    }
}
