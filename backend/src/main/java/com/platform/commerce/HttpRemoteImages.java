package com.platform.commerce;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Only https pictures from Meta's own picture hosts, no redirects, at most 6 MB and only real image types: a post cannot make the server fetch an arbitrary address. */
@Component
public class HttpRemoteImages implements RemoteImages {
    private static final List<String> HOSTS = List.of(".fbcdn.net", ".cdninstagram.com", ".fbsbx.com");
    private static final long MAX = 6_000_000L;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();

    static boolean allowed(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && HOSTS.stream().anyMatch(h -> host.endsWith(h) || host.equals(h.substring(1)));
        } catch (RuntimeException e) { return false; }
    }

    @Override
    public Optional<Image> fetch(String url) {
        if (url == null || !allowed(url)) return Optional.empty();
        try {
            HttpResponse<java.io.InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            String type = res.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (res.statusCode() != 200 || !(type.startsWith("image/jpeg") || type.startsWith("image/png") || type.startsWith("image/webp"))) { res.body().close(); return Optional.empty(); }
            try (var in = res.body()) {
                byte[] data = in.readNBytes((int) MAX + 1);
                return data.length == 0 || data.length > MAX ? Optional.empty() : Optional.of(new Image(data, type.startsWith("image/png") ? "image/png" : "image/jpeg"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) { return Optional.empty(); }
    }
}
