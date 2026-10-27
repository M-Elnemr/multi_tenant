package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.chat.AiProvider.ToolResult;
import com.platform.chat.AiProvider.Turn;
import java.util.ArrayList;
import java.util.List;

/**
 * The second look a careful salesperson takes before pressing send: the draft reply, the customer's own words and what the tools really returned go to the model once more, as a strict reviewer.
 * It answers either "fine" or a corrected reply. It never blocks a reply: when the review fails or its answer is not usable, the draft goes out (and the money and claims checks still run on whatever is sent).
 */
final class ReplyReview {
    private ReplyReview() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The reviewer's verdict: {@code reply} is the corrected text when {@code ok} is false. */
    record Verdict(boolean ok, String reply, List<String> problems) {}

    static final String SYSTEM = """
            You are the senior sales manager of an Egyptian online shop. You check ONE reply that your salesperson is about to send to a customer, and you fix it if needed.
            You get: the recent chat, the customer's newest message(s), the facts the tools returned for this reply (FACTS), how to address the customer, and the DRAFT.

            Check the draft strictly against this list:
            1. It answers EVERYTHING the customer asked in their newest message(s) (several lines may be several messages): nothing ignored, related questions answered together naturally, not a robotic question-by-question list. Two separate topics may be two messages separated by a line with only ⸻ (never more than two).
            2. Every fact about a product (what it is, who it is for, material, quality, origin, size, color, stock, price) is in FACTS or was said in the chat. No invented qualities («أصلي ١٠٠٪», «مستورد», «قطن مصري»...) unless FACTS says so; general care advice is fine when worded as general («عادةً…»).
            3. The products fit WHO it is for: never a kids' product (for boys, girls, babies) for an adult buying for themself, never women's products for a man buying for himself (unless a gift). If the draft recommends while it is unclear who it is for and the shop has the item for several groups, it should ask one short question instead («ليك ولا لحد تاني؟»).
            4. Respect and gender: the right masculine/feminine forms for this customer; «أستاذ/أستاذة + name» only as ADDRESSING says (never the wrong gender, never a guess); otherwise «حضرتك»/«يا فندم».
            5. Words: natural, correct Egyptian Arabic (or the customer's language and dialect), simple everyday words, no odd, poetic, literal-translation or wrong words, no typos, nothing that could be understood wrongly.
            6. No invented urgency, deadline, guarantee, review, promise of a call, and no discount tied to ordering now. A discount, if any, must be one the customer asked for or a kind gift, never pressure.
            7. Friendly and calm even if the customer is rude; never argues or lectures.
            8. Short (1-3 sentences per message), sounds like a real person, ends with exactly one step toward the sale, does not repeat a sentence the salesperson already sent in the chat.

            Answer with JSON only, no markdown:
            {"ok": true}  when the draft passes every point, or
            {"ok": false, "problems": ["short reason", ...], "reply": "the corrected reply"}
            The corrected reply keeps everything good in the draft (the products, prices and next step), fixes only what is wrong, adds NO price, amount, product, discount or fact that is not in the draft or FACTS, and is written in the customer's language and dialect, ready to send as it is.
            """;

    /** What the reviewer reads: the chat so far (short), the customer's newest words, the tool facts of this reply, how to address the customer, and the draft. */
    static String request(List<Turn> turns, String address, String draft) {
        StringBuilder chat = new StringBuilder(), newest = new StringBuilder(), facts = new StringBuilder();
        int lastModel = -1;
        for (int i = 0; i < turns.size(); i++) if ("model".equals(turns.get(i).role()) && turns.get(i).calls().isEmpty() && turns.get(i).text() != null && !turns.get(i).text().isBlank()) lastModel = i;
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            if ("tool".equals(t.role())) {
                for (ToolResult r : t.results()) facts.append("- ").append(r.name()).append(": ").append(clip(json(r.result()), 1500)).append('\n');
                continue;
            }
            if (t.text() == null || t.text().isBlank() || t.text().startsWith("[SYSTEM CHECK")) continue;
            if ("user".equals(t.role()) && i > lastModel) newest.append(t.text()).append('\n');
            else if (!t.calls().isEmpty()) continue;
            else chat.append("user".equals(t.role()) ? "Customer: " : "Salesperson: ").append(clip(t.text(), 400)).append('\n');
        }
        return "RECENT CHAT:\n" + clip(tail(chat.toString(), 2500), 2500)
                + "\nCUSTOMER'S NEWEST MESSAGE(S):\n" + clip(newest.toString(), 1200)
                + "\nFACTS (tool results for this reply" + (facts.length() == 0 ? "; none: the salesperson used no tool" : "") + "):\n" + clip(facts.toString(), 6000)
                + "\nADDRESSING:" + (address == null ? " not known, use «حضرتك»" : address)
                + "\nDRAFT:\n" + draft;
    }

    /** The reviewer's answer; null when it is not usable (the draft is then sent as it is). */
    static Verdict parse(String text) {
        if (text == null) return null;
        String raw = text.trim().replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
        int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JsonNode n = JSON.readTree(raw.substring(a, b + 1));
            if (!n.has("ok")) return null;
            List<String> problems = new ArrayList<>();
            for (JsonNode p : n.path("problems")) problems.add(p.asText());
            if (n.path("ok").asBoolean(false)) return new Verdict(true, null, problems);
            String reply = n.path("reply").asText("").trim();
            return reply.length() < 2 ? null : new Verdict(false, reply, problems);
        } catch (Exception e) {
            return null;
        }
    }

    private static String json(Object o) {
        try { return JSON.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    private static String clip(String s, int max) { return s == null ? "" : s.length() > max ? s.substring(0, max) + "…" : s; }

    private static String tail(String s, int max) { return s.length() > max ? s.substring(s.length() - max) : s; }
}
