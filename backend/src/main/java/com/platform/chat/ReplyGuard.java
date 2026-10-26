package com.platform.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Second line of defence after {@link MoneyGuard}: claims a salesperson must never make up to push a sale (the price is about to rise, the shop exports, a guarantee, a human will call). A claim is fine only
 * when the same words appear in what the assistant legitimately knows (the shop data, a tool result, the customer's own message, the owner's answer); otherwise the reply is rewritten once and then cut.
 */
final class ReplyGuard {
    private ReplyGuard() {}

    private record Rule(String name, Pattern pattern) {
        Rule(String name, String regex) { this(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)); }
    }

    private static final List<Rule> RULES = List.of(
            new Rule("a price rise", "(?:هيغلي|هيغلى|هتغلي|هتغلى|هيزيد|هتزيد|هيرتفع|هترتفع|هتبقى أغلى|هيبقى أغلى)|(?:price|prices)\\s+(?:will|are going to|is going to|are about to)\\s+(?:go up|increase|rise)|(?:prices?)\\s+(?:is|are)\\s+(?:going up|rising)"),
            new Rule("exporting or a brand", "(?:بيصدر|بتصدر|بنصدر|بيصدّر|مصدّر|مصدر\\s+بره|بره مصر)|\\bexport(?:s|ed|ing)?\\b"),
            new Rule("a guarantee", "(?:ضمان\\s+(?:سنة|سنتين|مدى الحياة|[0-9٠-٩]+)|lifetime warranty|[0-9]+[- ]year (?:warranty|guarantee))"),
            new Rule("a person calling", "(?:هيتصل بيك|هنتصل بيك|هتتصل بيك|خدمة العملاء هتتواصل|يتصل بك أحد|someone (?:will|from the team will) (?:call|phone) you|customer service will (?:call|contact))"));

    /** One claim found in a text. */
    record Claim(String kind, String raw) {}

    /** The claims in {@code text} that {@code facts} (everything the assistant legitimately knows) does not back up. */
    static List<Claim> violations(String text, String facts) {
        List<Claim> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(text);
            while (m.find()) {
                if (facts != null && r.pattern().matcher(facts).find()) continue;
                out.add(new Claim(r.name(), m.group()));
            }
        }
        return out;
    }

    /** The reply without the sentences that make an unbacked claim. */
    static String strip(String text, List<Claim> bad) {
        if (bad.isEmpty()) return text;
        StringBuilder out = new StringBuilder();
        for (String part : text.split("(?<=[.!؟?\\n])")) {
            boolean drop = false;
            for (Claim c : bad) if (part.contains(c.raw())) { drop = true; break; }
            if (!drop) out.append(part);
        }
        return out.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** What the model is told when its draft made a claim nobody can back up. */
    static String note(List<Claim> bad) {
        return "[SYSTEM CHECK, not from the customer] Your draft makes claims that nothing in the shop data, the tools or the chat supports (" + bad.stream().map(Claim::kind).distinct().toList()
                + "). A real salesperson never invents urgency, guarantees, exporting, brands or promises of a call to push a sale. Remove them, keep only facts from the tools, and write your reply again: "
                + "answer what the customer actually asked in simple words, then take one step toward the sale.";
    }
}
