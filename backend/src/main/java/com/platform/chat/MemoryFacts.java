package com.platform.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Structured memory of what a customer told the shop: budget, size, colour likes and dislikes, constraints, name, purchase intent. Facts are read from the customer's own messages by code
 * (no model call), each with the message it came from, a confidence and a freshness; a newer explicit statement about the same thing supersedes the older (the old row stays, marked
 * SUPERSEDED). Retrieval is query-aware: only facts that bear on the customer's current message reach the prompt, labelled as the customer's own words and never as shop data.
 * Nothing here is an authority for a price, stock, variant, order or payment status: those come from the tools. Phone numbers, addresses, e-mails and other identifiers are never stored.
 */
@Component
public class MemoryFacts {
    private static final Logger log = LoggerFactory.getLogger(MemoryFacts.class);

    /** A fact found in one message. {@code confidence} 1.0 = stated outright; lower = a reading of what they said. */
    public record Candidate(String type, String key, String value, double confidence, long ttlDays) {}

    /** A stored fact as retrieved for the prompt. */
    public record Fact(long id, String type, String key, String value, double confidence, String sourceText, Instant at, long sourceMessageId) {}

    private final JdbcClient jdbc;

    public MemoryFacts(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---- extraction (pure) ------------------------------------------------------------------------------------------------------

    private static final Map<String, String> COLORS = new LinkedHashMap<>();
    static {
        for (String[] c : new String[][] {
                {"black", "اسود|اسوود|eswed|aswad|black|دارك"}, {"white", "ابيض|abyad|abyd|white"}, {"red", "احمر|ahmar|red"}, {"blue", "ازرق|azra2|azraq|blue"},
                {"green", "اخضر|akhdar|green"}, {"beige", "بيج|beige"}, {"brown", "بني|brown|bonny"}, {"navy", "كحلي|navy|kohly"}, {"grey", "رمادي|gray|grey|ramady"},
                {"pink", "بينك|وردي|pink"}, {"yellow", "اصفر|asfar|yellow"}, {"olive", "زيتي|olive"}, {"orange", "برتقالي|orange"}, {"purple", "موف|بنفسجي|purple"}})
            COLORS.put(c[0], c[1]);
    }
    private static final String LIKE = "(?:بحب|بفضل|افضل|عايز|عاوز|عايزه|عاوزه|محتاج|محتاجه|يعجبني|حابب|حابه|3ayez|3ayz|3ayza|3awz|3awez|3awza|ayez|ba7eb|bahib|b7eb|love|like|want|prefer|need)";
    private static final String AVOID = "(?:مش بحب|مش عايز|مش عاوز|مش عايزه|ما بحبش|مبحبش|بكره|مش حابب|من غير|بدون|dont like|do not like|don t like|dont want|do not want|don t want|hate|avoid|no|without)";
    /** «ميزانيتي ٦٠٠», «ميزانيتي بقت ٨٠٠», "my budget is now 750": the budget word, up to two small words (بقت, is, now...), the number. */
    private static final Pattern BUDGET_WORD = Pattern.compile("(ميزانيت\\S*|budget)\\s*(?:(?:هي|هو|بقت|بقي|بقيت|بقا|اتغيرت|اصبحت|حوالي|تقريبا|about|around|is|now|became|بقى)\\s+){0,2}(\\d+(?:[.,]\\d+)?)");
    private static final Pattern BUDGET = Pattern.compile("(في حدود|حدود|اقصي سعر|اقصي|مش اكتر من|مش اكثر من|ماكتر من|لحد|حد اقصي|up to|under|max(?:imum)?)\\s*(?:هي|هو|حوالي|تقريبا|about|around|is)?\\s*(\\d+(?:[.,]\\d+)?)");
    private static final Pattern SIZE = Pattern.compile("(?:^|\\s)و?(مقاسي|مقاس|size|بلبس|بلبسه|بلبسها|i wear|i m|im)\\s*(?:هو|is|بتاعي)?\\s*(xxxl|xxl|3xl|2xl|xl|xs|s|m|l|اكس لارج|اكسلارج|ميديم|لارج|سمول|\\d{2})(?=\\s|$)");
    private static final Pattern INTENT = Pattern.compile("(هاخده|هاخدها|هاخدهم|هشتريه|هشتريها|خلاص عايز اطلب|عايز اطلب|عاوز اطلب|يلا اطلب|i will take it|i ll take it|ill take it|lets order)");
    private static final Pattern CONSTRAINT = Pattern.compile("(مش عايز|مش عاوز|مش بحب|ما بحبش|مبحبش|من غير|بدون|dont want|do not want|without)\\s+((?:\\S+\\s*){1,4})");
    private static final Pattern RECALL = Pattern.compile("(فاكر|فاكره|تفتكر|افتكر|قلتلك|قلت لك|كنت قلت|اللي قلته|remember|i told you|i said|earlier|as i said|before)");

    static String digits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(c >= '٠' && c <= '٩' ? (char) ('0' + (c - '٠')) : c >= '۰' && c <= '۹' ? (char) ('0' + (c - '۰')) : c);
        return b.toString();
    }

    static String normalize(String text) { return FastAnswers.norm(digits(text == null ? "" : text)); }

    private static String sizeValue(String raw) {
        return switch (raw) { case "اكس لارج", "اكسلارج", "xl" -> "XL"; case "ميديم" -> "M"; case "لارج" -> "L"; case "سمول" -> "S"; case "3xl" -> "XXXL"; case "2xl" -> "XXL";
            default -> raw.toUpperCase(Locale.ROOT); };
    }

    /** The facts one message states, in the order they appear. Pure: no database. Sensitive identifiers are never part of a fact. */
    public static List<Candidate> extract(String text) {
        List<Candidate> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String n = normalize(text);
        for (Pattern bp : new Pattern[] {BUDGET_WORD, BUDGET}) {
            Matcher b = bp.matcher(n);
            while (b.find()) {
                String num = b.group(2).replace(',', '.');
                double v; try { v = Double.parseDouble(num); } catch (NumberFormatException e) { continue; }
                if (v < 10 || v > 10_000_000 || num.replaceAll("\\D", "").length() >= 9) continue;   // not a budget: a short number, or a phone number
                String value = num.endsWith(".0") ? num.substring(0, num.length() - 2) : num;
                if (out.stream().anyMatch(c -> c.type().equals("BUDGET") && c.value().equals(value))) continue;   // the same statement matched by both patterns
                out.add(new Candidate("BUDGET", "budget", value, bp == BUDGET_WORD ? 1.0 : 0.75, 90));
            }
        }
        Matcher s = SIZE.matcher(n);
        while (s.find()) {
            String raw = s.group(2);
            if (raw.matches("\\d{2}") && (Integer.parseInt(raw) < 20 || Integer.parseInt(raw) > 60)) continue;   // a shoe or trouser size, not a stray number
            out.add(new Candidate("SIZE", "size", sizeValue(raw), 1.0, 90));
        }
        for (var c : COLORS.entrySet()) {
            Matcher m = Pattern.compile("(^|\\s)((?:" + AVOID + "|" + LIKE + ")\\s+(?:\\S+\\s+){0,2}?)?(?:ال)?(?:" + c.getValue() + ")(?=\\s|$)").matcher(n);
            while (m.find()) {
                String lead = m.group(2);
                if (lead == null) continue;   // a colour merely mentioned says nothing about what they want
                boolean avoid = Pattern.compile(AVOID).matcher(lead).find();
                out.add(new Candidate("COLOR", "color:" + c.getKey(), avoid ? "avoid" : "like", 0.9, 90));
            }
        }
        Matcher k = CONSTRAINT.matcher(n);
        while (k.find()) {
            String what = k.group(2).trim();
            if (what.isEmpty() || COLORS.values().stream().anyMatch(re -> Pattern.compile("^(?:ال)?(?:" + re + ")\\b").matcher(what).find())) continue;   // colours are handled above
            if (what.matches(".*\\d{6,}.*") || what.contains("@")) continue;   // never store an identifier
            String[] w = what.split("\\s+");
            String value = String.join(" ", java.util.Arrays.copyOf(w, Math.min(w.length, 4)));
            out.add(new Candidate("CONSTRAINT", "constraint:" + w[0], (k.group(1).startsWith("بدون") || k.group(1).startsWith("من غير") || k.group(1).startsWith("without") ? "without " : "not ") + value, 0.85, 90));
        }
        String name = ChatService.statedName(text);
        if (name != null && !name.isBlank()) out.add(new Candidate("NAME", "name", name, 1.0, 365));
        if (INTENT.matcher(n).find()) out.add(new Candidate("INTENT", "intent:purchase", "wants to order", 0.6, 3));   // a reading, not a confirmation: shown as inferred
        return out;
    }

    // ---- storage ------------------------------------------------------------------------------------------------------------------

    /**
     * Reads the facts out of a customer message (line by line, so a later line can correct an earlier one) and stores them for this conversation. The same fact again changes nothing; a
     * different value for the same thing supersedes the older one. Never throws: memory is not allowed to fail a reply.
     */
    public int record(UUID tenantId, UUID conversationId, long messageId, String text) {
        int stored = 0;
        try {
            if (text == null) return 0;
            for (String line : text.split("\\n")) {
                for (Candidate c : extract(line)) if (store(tenantId, conversationId, messageId, line, c)) stored++;
            }
        } catch (RuntimeException e) { log.warn("Memory facts not stored: {}", e.toString()); }
        return stored;
    }

    private boolean store(UUID tenantId, UUID conv, long messageId, String line, Candidate c) {
        var old = jdbc.sql("SELECT id, value, confidence, expires_at < now() AS expired FROM chat.memory_facts WHERE conversation_id = :c AND fact_key = :k AND status = 'ACTIVE'")
                .param("c", conv).param("k", c.key()).query().listOfRows().stream().findFirst().orElse(null);
        if (old != null && !Boolean.TRUE.equals(old.get("expired")) && c.value().equals(old.get("value"))) return false;   // repeated information
        if (old != null && !Boolean.TRUE.equals(old.get("expired")) && c.confidence() < ((Number) old.get("confidence")).doubleValue() - 0.2 && "INTENT".equals(c.type())) return false;   // a weak reading never replaces a firm fact
        String snippet = MessageReplies.clip(line.strip().replaceAll("\\s+", " "), 240);
        long id = jdbc.sql("""
                INSERT INTO chat.memory_facts (tenant_id, conversation_id, type, fact_key, value, source_message_id, source_text, confidence, expires_at)
                VALUES (:t, :c, :ty, :k, :v, :m, :s, :cf, now() + make_interval(days => :d))
                ON CONFLICT DO NOTHING RETURNING id
                """).param("t", tenantId).param("c", conv).param("ty", c.type()).param("k", c.key()).param("v", c.value()).param("m", messageId).param("s", snippet).param("cf", (float) c.confidence()).param("d", (int) c.ttlDays())
                .query(Long.class).optional().orElse(-1L);
        if (old != null) {
            jdbc.sql("UPDATE chat.memory_facts SET status = CASE WHEN expires_at < now() THEN 'EXPIRED' ELSE 'SUPERSEDED' END, superseded_by = NULLIF(:n, -1) WHERE id = :o").param("n", id).param("o", ((Number) old.get("id")).longValue()).update();
            if (id < 0) {   // the unique index refused the new row while the old one was still active: retry now that the old one is out of the way
                id = jdbc.sql("""
                        INSERT INTO chat.memory_facts (tenant_id, conversation_id, type, fact_key, value, source_message_id, source_text, confidence, expires_at)
                        VALUES (:t, :c, :ty, :k, :v, :m, :s, :cf, now() + make_interval(days => :d)) RETURNING id
                        """).param("t", tenantId).param("c", conv).param("ty", c.type()).param("k", c.key()).param("v", c.value()).param("m", messageId).param("s", snippet).param("cf", (float) c.confidence()).param("d", (int) c.ttlDays())
                        .query(Long.class).single();
                jdbc.sql("UPDATE chat.memory_facts SET superseded_by = :n WHERE id = :o").param("n", id).param("o", ((Number) old.get("id")).longValue()).update();
            }
        }
        return true;
    }

    // ---- query-aware retrieval -----------------------------------------------------------------------------------------------------

    private static final Map<String, List<String>> TRIGGERS = Map.of(
            "BUDGET", List.of("ميزانيه", "سعر", "بكام", "كام", "غالي", "غاليه", "ارخص", "رخيص", "تكلف", "خصم", "عرض", "budget", "price", "cheap", "cheaper", "expensive", "cost", "afford", "كده كام", "اوفر", "تمن"),
            "SIZE", List.of("مقاس", "مقاسي", "size", "يناسب", "ضيق", "واسع", "لارج", "ميديم", "سمول", "fit", "fits", "يلبس", "بلبس"),
            "COLOR", List.of("لون", "الوان", "color", "colour", "colors", "اسود", "ابيض", "احمر", "ازرق", "اخضر", "بيج", "بني", "كحلي", "رمادي", "غامق", "فاتح"),
            "CONSTRAINT", List.of("رشح", "اقترح", "ايه", "عندكم", "عايز", "محتاج", "recommend", "suggest", "show", "have", "looking", "اختار", "بديل", "غير"),
            "INTENT", List.of("اطلب", "اشتري", "اوردر", "order", "buy", "تمام", "خلاص", "اكمل", "كمل"),
            "NAME", List.of("اسمي", "ناديني", "اسمى", "my name", "call me"));

    /**
     * The facts that bear on this message, best first (at most {@code limit}). A message that asks the assistant to recall («فاكر؟», «قلتلك», "I told you") gets every active fact; any other
     * message gets only the facts whose subject it touches (a price question touches the budget, a colour word the colour preferences) or whose own words it shares. Nothing relevant: nothing.
     */
    public List<Fact> relevant(UUID tenantId, UUID conversationId, String message, int limit) { return relevant(tenantId, conversationId, message, limit, Long.MAX_VALUE); }

    /**
     * Same, but only facts whose source message is OLDER than {@code beforeMessageId} (the oldest message the model is given as history): what was said inside that window is current
     * conversation, already in front of the model; this memory is the historical part, so the two are not mixed or repeated.
     */
    public List<Fact> relevant(UUID tenantId, UUID conversationId, String message, int limit, long beforeMessageId) {
        String n = normalize(message);
        List<Fact> all = active(tenantId, conversationId).stream().filter(f -> f.sourceMessageId() < beforeMessageId).toList();
        if (all.isEmpty() || n.isBlank()) return List.of();
        boolean recall = RECALL.matcher(n).find();
        Set<String> tokens = new LinkedHashSet<>(List.of(n.split("\\s+")));
        List<Map.Entry<Fact, Double>> scored = new ArrayList<>();
        for (Fact f : all) {
            double score = 0;
            for (String t : TRIGGERS.getOrDefault(f.type(), List.of())) if (n.contains(t)) { score += 2; break; }
            String own = normalize(f.value() + " " + f.key().replace("color:", "").replace("constraint:", ""));
            for (String w : own.split("\\s+")) if (w.length() >= 3 && tokens.contains(w)) score += 3;
            if (f.type().equals("COLOR") && COLORS.get(f.key().substring(6)) != null && Pattern.compile("(?:^|\\s)(?:ال)?(?:" + COLORS.get(f.key().substring(6)) + ")(?=\\s|$)").matcher(n).find()) score += 4;   // the very colour they ask about
            if (recall) score += 5;
            if (score > 0) scored.add(Map.entry(f, score));
        }
        scored.sort(Map.Entry.<Fact, Double>comparingByValue().reversed().thenComparing(e -> e.getKey().at(), Comparator.reverseOrder()));
        List<Fact> out = new ArrayList<>();
        for (var e : scored) if (out.size() < (recall ? Math.max(limit, 8) : limit)) out.add(e.getKey());
        return out;
    }

    /** The active, unexpired facts of one conversation of one shop (never another's), newest first. */
    public List<Fact> active(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                SELECT id, type, fact_key, value, confidence, source_text, created_at, coalesce(source_message_id, 0) AS src FROM chat.memory_facts
                WHERE tenant_id = :t AND conversation_id = :c AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > now()) ORDER BY created_at DESC, id DESC
                """).param("t", tenantId).param("c", conversationId).query((rs, i) -> new Fact(rs.getLong("id"), rs.getString("type"), rs.getString("fact_key"), rs.getString("value"), rs.getDouble("confidence"),
                rs.getString("source_text"), rs.getTimestamp("created_at").toInstant(), rs.getLong("src"))).list();
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Africa/Cairo"));

    /** The prompt section for these facts, or "" when there are none. Provenance (what they said and when) is shown; uncertain facts are marked as inferred. */
    public static String block(List<Fact> facts) {
        if (facts == null || facts.isEmpty()) return "";
        StringBuilder b = new StringBuilder("\nWHAT THE CUSTOMER TOLD US EARLIER (their own words, picked out by code because they bear on this message; this is NOT shop data and never a price, stock, variant, order or payment status: those come only from the tools). "
                + "A newer statement of theirs in the conversation above beats anything here.\n");
        for (Fact f : facts) {
            String label = switch (f.type()) {
                case "BUDGET" -> "Their budget: " + f.value() + " (what they want to spend, NOT a product's price)";
                case "SIZE" -> "Their size: " + f.value();
                case "COLOR" -> ("avoid".equals(f.value()) ? "Colour they do NOT want: " : "Colour they like: ") + f.key().substring(6);
                case "CONSTRAINT" -> "A condition they set: " + f.value();
                case "NAME" -> "Their name: " + f.value();
                default -> "Their purchase intent: " + f.value();
            };
            b.append("  - ").append(label).append(f.confidence() < 0.8 ? " [inferred, not confirmed]" : "").append("  — said on ").append(DAY.format(f.at())).append(": «").append(f.sourceText() == null ? "" : f.sourceText()).append("»\n");
        }
        return b.toString();
    }
}
