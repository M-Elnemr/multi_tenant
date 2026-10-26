package com.platform.chat;

import com.platform.open.OpenUrls;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * When the AI seller suggests products in a WhatsApp, Messenger or Instagram chat, the customer also gets each product's picture with its name, price and link,
 * the way a salesperson would send a photo. Only the products the AI chose with show_products or actually named in its answer are shared (at most three), and a product with no picture is sent as its link alone.
 */
@Service
public class ProductShare {
    public record Card(String imageUrl, String caption, String url) {}

    static final int MAX = 3;
    private final OpenUrls urls;

    public ProductShare(OpenUrls urls) { this.urls = urls; }

    private static String fold(String s) { return s == null ? "" : s.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه').toLowerCase().trim(); }

    private static String money(Object minor, Object currency) {
        if (!(minor instanceof Number n)) return "";
        double v = n.longValue() / 100.0;
        String c = "EGP".equals(currency) ? "جنيه" : String.valueOf(currency == null ? "" : currency);
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v)) + " " + c;
    }

    /** The cards to send after {@code answer}: the searched products whose name the answer mentions. */
    public List<Card> pick(UUID tenantId, String answer, List<Map<String, Object>> products) {
        if (products == null || products.isEmpty() || answer == null || answer.isBlank()) return List.of();
        String text = fold(answer);
        String host = urls.hosts(List.of(tenantId)).get(tenantId);
        if (host == null) return List.of();
        List<Card> out = new ArrayList<>();
        java.util.Set<Object> seen = new java.util.HashSet<>();
        // the products the AI sent on purpose (show_products) go first, then the searched ones whose name the answer repeats
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (var p : products) if (Boolean.TRUE.equals(p.get("send"))) ordered.add(p);
        for (var p : products) if (!Boolean.TRUE.equals(p.get("send"))) ordered.add(p);
        for (var p : ordered) {
            if (out.size() >= MAX) break;
            String name = String.valueOf(p.get("name"));
            boolean sent = Boolean.TRUE.equals(p.get("send"));
            if (!seen.add(p.get("slug")) || (!sent && (fold(name).length() < 3 || !text.contains(fold(name))))) continue;
            String url = urls.baseUrl(host) + "/products/" + java.net.URLEncoder.encode(String.valueOf(p.get("slug")), java.nio.charset.StandardCharsets.UTF_8);
            String price = money(p.get("priceMinor"), p.get("currency"));
            String caption = name + (price.isBlank() ? "" : "\n" + price) + "\n" + url;
            String image = urls.image(host, (String) p.get("imageMediaBase"), (String) p.get("imageMediaExt"), (String) p.get("imageUrl"), "medium");
            out.add(new Card(image, caption, url));
        }
        return out;
    }
}
