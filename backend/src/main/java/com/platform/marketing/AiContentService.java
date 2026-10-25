package com.platform.marketing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.chat.AiProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Writes the words for posts and proposes ad settings. Always answers: when the model is unavailable or answers badly, plain templates are used so the owner is never stuck. */
@Service
public class AiContentService {
    private static final Logger log = LoggerFactory.getLogger(AiContentService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AiProvider ai;

    private final com.platform.chat.AiUsageService aiUsage;

    public AiContentService(AiProvider ai, com.platform.chat.AiUsageService aiUsage) { this.ai = ai; this.aiUsage = aiUsage; }

    /** What the model is told about the thing being promoted. {@code kind} PRODUCT or SHOP. */
    public record Subject(String kind, String shopName, String shopAbout, String name, String description, String brand, String category, long priceMinor, Long compareAtMinor, String currency, String url, List<String> topProducts) {}

    public record Draft(String caption, List<String> hashtags, String imagePrompt, boolean fromAi) {}

    private static String market(String country) { return "SA".equals(country) ? "Saudi Arabia" : "AE".equals(country) ? "the United Arab Emirates" : "Egypt"; }

    public Draft post(Subject s, String language, String tone, String country) {
        boolean ar = !"en".equalsIgnoreCase(language);
        String system = "You write social media posts for an online shop selling in " + market(country) + ". Write in " + (ar ? "Arabic (friendly, natural, easy dialect-neutral wording)" : "English")
                + ". Tone: " + (tone == null || tone.isBlank() ? "friendly and persuasive" : tone) + ". Be truthful: never invent discounts, materials, certifications or stock claims that are not in the facts. "
                + "Reply with ONLY a JSON object: {\"caption\": string (max 450 characters, a hook, the key benefit, price if given, a call to action, a few emoji), \"hashtags\": [5 to 8 short hashtags without #, relevant to the market], "
                + "\"imagePrompt\": string (English, one paragraph describing a clean professional square 1:1 social media image for this, no text or logos in the image)}.";
        String facts = "PRODUCT".equals(s.kind())
                ? "Shop: " + s.shopName() + "\nProduct: " + s.name() + (s.brand() == null || s.brand().isBlank() ? "" : "\nBrand: " + s.brand()) + (s.category() == null ? "" : "\nCategory: " + s.category())
                  + "\nPrice: " + price(s.priceMinor(), s.currency()) + (s.compareAtMinor() != null && s.compareAtMinor() > s.priceMinor() ? " (was " + price(s.compareAtMinor(), s.currency()) + ")" : "")
                  + (s.description() == null || s.description().isBlank() ? "" : "\nDescription: " + clip(s.description(), 600))
                : "Shop: " + s.shopName() + (s.shopAbout() == null || s.shopAbout().isBlank() ? "" : "\nAbout: " + clip(s.shopAbout(), 500)) + (s.topProducts().isEmpty() ? "" : "\nPopular products: " + String.join(", ", s.topProducts()));
        try {
            AiProvider.Turn t = ai.next(system, List.of(AiProvider.Turn.user(facts)), List.of());
            aiUsage.recordCall(null, "SOCIAL_POST", t);
            Map<String, Object> m = json(t.text());
            String caption = str(m.get("caption"));
            if (caption != null && !caption.isBlank()) return new Draft(clip(caption, 900), tags(m.get("hashtags")), str(m.get("imagePrompt")) == null ? defaultImagePrompt(s) : str(m.get("imagePrompt")), true);
        } catch (RuntimeException e) {
            log.info("AI caption fell back to a template: {}", e.getMessage());
        }
        return fallback(s, ar);
    }

    private Draft fallback(Subject s, boolean ar) {
        String text;
        if ("PRODUCT".equals(s.kind())) {
            text = ar ? "✨ " + s.name() + "\n" + (s.description() == null || s.description().isBlank() ? "" : clip(s.description().replaceAll("\\s+", " "), 160) + "\n") + "💰 " + price(s.priceMinor(), s.currency()) + "\nاطلبه الآن من " + s.shopName() + " 👇"
                    : "✨ " + s.name() + "\n" + (s.description() == null || s.description().isBlank() ? "" : clip(s.description().replaceAll("\\s+", " "), 160) + "\n") + "💰 " + price(s.priceMinor(), s.currency()) + "\nOrder now from " + s.shopName() + " 👇";
        } else {
            text = ar ? "🛍️ أهلاً بكم في " + s.shopName() + "\n" + (s.shopAbout() == null ? "" : clip(s.shopAbout().replaceAll("\\s+", " "), 200) + "\n") + "تسوّقوا الآن واستمتعوا بالتوصيل لحد باب البيت 🚚"
                    : "🛍️ Welcome to " + s.shopName() + "\n" + (s.shopAbout() == null ? "" : clip(s.shopAbout().replaceAll("\\s+", " "), 200) + "\n") + "Shop now and get it delivered to your door 🚚";
        }
        return new Draft(text, List.of(ar ? "تسوق_اونلاين" : "shoponline", ar ? "عروض" : "deals"), defaultImagePrompt(s), false);
    }

    private static String defaultImagePrompt(Subject s) {
        return "PRODUCT".equals(s.kind()) ? "A clean, bright, professional lifestyle photo of " + s.name() + " on a simple soft background, centered, premium e-commerce style, square 1:1, no text, no logos"
                : "A welcoming modern online shop themed banner for " + s.shopName() + ", clean colors, shopping bags and parcels, square 1:1, no text";
    }

    // ---- ad suggestions -------------------------------------------------------------------------------------------

    public record AdContext(String country, String currency, String productName, String description, String category, long priceMinor, String shopName) {}

    /** Settings proposed for a new campaign. The numbers have rule-based defaults; the model improves the words, audience and reasoning. */
    public Map<String, Object> adSuggestion(AdContext c, String language) {
        boolean ar = !"en".equalsIgnoreCase(language);
        Map<String, Object> out = new LinkedHashMap<>();
        long price = Math.max(0, c.priceMinor());
        long budget = switch (c.currency() == null ? "EGP" : c.currency()) { case "SAR" -> 4000; case "AED" -> 4000; default -> price > 150000 ? 30000 : price > 50000 ? 20000 : 12000; };
        out.put("objective", "OUTCOME_TRAFFIC");
        out.put("dailyBudgetMinor", budget);
        out.put("currency", c.currency());
        out.put("durationDays", 7);
        out.put("countries", List.of(c.country() == null ? "EG" : c.country()));
        out.put("ageMin", 20);
        out.put("ageMax", 50);
        out.put("genders", List.of());
        out.put("interests", c.category() == null ? List.of() : List.of(c.category()));
        out.put("cta", "SHOP_NOW");
        out.put("headline", c.productName() == null ? c.shopName() : clip(c.productName(), 38));
        out.put("primaryText", ar ? "اطلب الآن من " + c.shopName() + " 🚚 توصيل لحد باب البيت" : "Order now from " + c.shopName() + " 🚚 delivered to your door");
        out.put("reasoning", ar ? "اقتراح تلقائي مبني على سعر المنتج والسوق." : "Automatic suggestion based on the product price and market.");
        out.put("fromAi", false);
        String system = "You are a performance marketing expert for small online shops in " + market(c.country()) + ". Suggest settings for ONE Meta (Facebook/Instagram) ad campaign that brings visitors to the shop. "
                + "Reply with ONLY a JSON object: {\"objective\": one of OUTCOME_TRAFFIC, OUTCOME_ENGAGEMENT, OUTCOME_AWARENESS, \"dailyBudgetMinor\": integer in minor units of " + c.currency() + " (cents), a sensible small-business daily budget, "
                + "\"durationDays\": integer 3-30, \"ageMin\": integer>=18, \"ageMax\": integer<=65, \"genders\": [] for everyone or [\"male\"] or [\"female\"], \"interests\": [up to 5 short interest names in English as they exist on Meta], "
                + "\"headline\": string max 40 chars in " + (ar ? "Arabic" : "English") + ", \"primaryText\": string max 200 chars in " + (ar ? "Arabic" : "English") + ", \"reasoning\": one short sentence in " + (ar ? "Arabic" : "English") + " on why these choices}.";
        String facts = "Shop: " + c.shopName() + (c.productName() == null ? "" : "\nProduct: " + c.productName() + "\nPrice: " + price(price, c.currency()) + (c.category() == null ? "" : "\nCategory: " + c.category()) + (c.description() == null ? "" : "\nDescription: " + clip(c.description(), 400)));
        try {
            AiProvider.Turn turn = ai.next(system, List.of(AiProvider.Turn.user(facts)), List.of());
            aiUsage.recordCall(null, "AD_SUGGESTION", turn);
            Map<String, Object> m = json(turn.text());
            String obj = str(m.get("objective"));
            if (obj != null && List.of("OUTCOME_TRAFFIC", "OUTCOME_ENGAGEMENT", "OUTCOME_AWARENESS").contains(obj)) out.put("objective", obj);
            if (m.get("dailyBudgetMinor") instanceof Number n && n.longValue() >= budget / 4 && n.longValue() <= budget * 10) out.put("dailyBudgetMinor", n.longValue());
            if (m.get("durationDays") instanceof Number n && n.intValue() >= 3 && n.intValue() <= 30) out.put("durationDays", n.intValue());
            if (m.get("ageMin") instanceof Number n && n.intValue() >= 18 && n.intValue() <= 60) out.put("ageMin", n.intValue());
            if (m.get("ageMax") instanceof Number n && n.intValue() >= 20 && n.intValue() <= 65 && n.intValue() >= ((Number) out.get("ageMin")).intValue()) out.put("ageMax", n.intValue());
            if (m.get("genders") instanceof List<?> g) out.put("genders", g.stream().map(String::valueOf).filter((x) -> x.equals("male") || x.equals("female")).distinct().toList());
            List<String> in = tags(m.get("interests"));
            if (!in.isEmpty()) out.put("interests", in.stream().limit(5).toList());
            if (str(m.get("headline")) != null) out.put("headline", clip(str(m.get("headline")), 40));
            if (str(m.get("primaryText")) != null) out.put("primaryText", clip(str(m.get("primaryText")), 220));
            if (str(m.get("reasoning")) != null) out.put("reasoning", clip(str(m.get("reasoning")), 240));
            out.put("fromAi", true);
        } catch (RuntimeException e) {
            log.info("AI ad suggestion fell back to defaults: {}", e.getMessage());
        }
        return out;
    }

    /** Three different texts for the same ad (different hooks: benefit, price/offer, trust). Never invents discounts; falls back to fixed templates when the model is unavailable. */
    public List<String[]> adVariants(AdContext c, String language) {
        boolean ar = !"en".equalsIgnoreCase(language);
        String name = c.productName() == null ? c.shopName() : clip(c.productName(), 30);
        List<String[]> out = new ArrayList<>();
        String system = "You write Meta (Facebook/Instagram) ad copy for small shops in " + market(c.country()) + ". Write THREE clearly different versions for the same ad in " + (ar ? "Arabic (natural, friendly)" : "English")
                + ": 1) benefit-led, 2) price or convenience-led (cash on delivery, delivery to the door) without inventing any discount, 3) trust-led (easy returns only if stated in the facts, otherwise quality and service). "
                + "Reply with ONLY a JSON object: {\"variants\": [{\"headline\": string max 38 chars, \"primaryText\": string max 180 chars}, x3]}. Never invent prices, discounts, materials or guarantees.";
        String facts = "Shop: " + c.shopName() + (c.productName() == null ? "" : "\nProduct: " + c.productName() + "\nPrice: " + price(Math.max(0, c.priceMinor()), c.currency()) + (c.description() == null ? "" : "\nDescription: " + clip(c.description(), 300)));
        try {
            AiProvider.Turn turn = ai.next(system, List.of(AiProvider.Turn.user(facts)), List.of());
            aiUsage.recordCall(null, "AD_VARIANTS", turn);
            Map<String, Object> m = json(turn.text());
            if (m.get("variants") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> v && str(v.get("headline")) != null && str(v.get("primaryText")) != null && out.size() < 3)
                out.add(new String[]{clip(str(v.get("headline")), 38), clip(str(v.get("primaryText")), 200)});
        } catch (RuntimeException e) {
            log.info("AI ad variants fell back to templates: {}", e.getMessage());
        }
        if (out.size() >= 2) return out;
        out.clear();
        out.add(ar ? new String[]{clip(name, 38), "اكتشف " + name + " من " + c.shopName() + " ✨ جودة تستاهل وتوصيل لحد باب البيت"} : new String[]{clip(name, 38), "Discover " + name + " from " + c.shopName() + " ✨ quality you will love, delivered to your door"});
        out.add(ar ? new String[]{"اطلب الآن وادفع عند الاستلام", name + " متاح دلوقتي 🚚 اطلبه من " + c.shopName() + " وادفع كاش لما يوصلك"} : new String[]{"Order now, pay on delivery", name + " is available now 🚚 order from " + c.shopName() + " and pay cash on delivery"});
        out.add(ar ? new String[]{"اختيار آلاف العملاء", c.shopName() + " بيهتم بكل أوردر 🌸 تابع طلبك خطوة بخطوة لحد ما يوصلك"} : new String[]{"Shop with confidence", c.shopName() + " takes care of every order 🌸 track yours step by step until it arrives"});
        return out;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    static Map<String, Object> json(String text) {
        if (text == null) throw new IllegalArgumentException("empty");
        String t = text.strip();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("no json");
        try { return JSON.readValue(t.substring(a, b + 1), new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (Exception e) { throw new IllegalArgumentException("bad json"); }
    }

    private static String str(Object o) { return o instanceof String s && !s.isBlank() ? s.trim() : null; }

    private static List<String> tags(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) { String s = String.valueOf(x).replace("#", "").replaceAll("\\s+", "_").trim(); if (!s.isEmpty() && out.size() < 10) out.add(s); }
        return out;
    }

    static String price(long minor, String currency) { return java.math.BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString() + " " + (currency == null ? "EGP" : currency); }

    static String clip(String s, int n) { return s == null ? null : (s.length() > n ? s.substring(0, n - 1).stripTrailing() + "…" : s); }
}
