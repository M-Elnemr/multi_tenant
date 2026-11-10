package com.platform.chat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The quantity a customer stated in one message, read conservatively: only signals that cannot be something else count, because the result is used to LOCK a quantity (code and model
 * must keep it) and a wrong reading would force a wrong order. Strong signals are a number word or the dual («اتنين», «تلاتة», «قطعتين», the dual of the product's noun), a lone
 * «واحد» in a short message, and a digit that is alone or with one word, next to a unit («٣ قطع», «×2») or right after a request word («عايز ٣»). Numbers of an address, a mobile number, a
 * measure («٢٠ وات», «٦٤ جيجا»), a size («مقاس ٤٢») and any value of the product's own options are never a quantity.
 */
final class OrderQuantity {
    private OrderQuantity() {}

    private static final Set<String> UNITS = Set.of("قطعه", "قطع", "حبه", "حبات", "حته", "حتت", "وحدات", "وحده", "pcs", "pc", "pieces", "piece", "x", "عدد", "كرتونه", "علبه", "علب", "زوج", "ازواج", "كيس", "اكياس");
    private static final Set<String> REQUEST = Set.of("عايز", "عايزه", "عاوز", "عاوزه", "عايزين", "هاخد", "اخد", "خد", "محتاج", "محتاجه", "ابغي", "ابغى", "ممكن", "خليهم", "خليها", "خليه", "need", "want", "take", "get");
    private static final Set<String> NOT_A_COUNT_BEFORE = Set.of("مقاس", "size", "رقم", "مقاسي", "وزن", "طول", "عرض", "سعر", "بسعر", "ب", "بـ", "حد", "لحد", "ميزانيه", "ميزانيتي", "الدور", "عماره", "شقه");
    private static final Set<String> MEASURE_AFTER = Set.of("جيجا", "جيجابايت", "gb", "تيرا", "tb", "وات", "w", "سم", "مم", "cm", "mm", "ml", "مل", "لتر", "جرام", "جم", "كيلو", "كجم", "kg", "g", "بوصه", "انش", "inch",
            "جنيه", "ج", "egp", "ريال", "دولار", "دقيقه", "ساعه", "يوم", "ايام", "شهر", "سنه", "سنين", "اسبوع", "%", "فولت", "امبير", "ميجا", "mp", "hz", "k");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{3,}");
    private static final Pattern DIGIT_TOKEN = Pattern.compile("^(\\d{1,2})$");

    /**
     * The quantity in {@code text}, which is one customer message already normalised with the product names taken out and the digits written Western (see ChatService.withoutNames); the
     * last strong signal wins. {@code optionValues} are the product's option values («128 جيجا», «42»), {@code dual} the dual of its noun («قميصين») or null.
     */
    static Optional<Integer> in(String text, String optionValues, String dual) {
        if (text == null || text.isBlank()) return Optional.empty();
        String t = ChatService.ADDRESS_NUMBER.matcher(" " + text + " ").replaceAll(" ");
        t = LONG_DIGITS.matcher(t).replaceAll(" ");
        List<String> tokens = java.util.Arrays.stream(t.split("[^\\p{L}\\p{N}%]+")).filter(s -> !s.isEmpty()).toList();
        String options = optionValues == null ? "" : FastAnswers.norm(MoneyGuard.digits(optionValues));
        Integer found = null;
        for (int i = 0; i < tokens.size(); i++) {
            String w = tokens.get(i);
            String prev = i > 0 ? tokens.get(i - 1) : "", next = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            Integer word = FactCheck.NUMBER_WORDS.get(w);
            if (word != null && !w.equals("الاتنين")) found = word;   // «الاتنين» is also Monday and «both»: never a stated quantity
            else if (w.equals("قطعتين") || w.equals("حتتين") || dual != null && (w.equals(dual) || w.equals("ال" + dual))) found = 2;
            else if ((w.equals("واحد") || w.equals("واحده") || w.equals("وحده") || w.equals("one")) && tokens.size() <= 4) found = 1;
            else if (DIGIT_TOKEN.matcher(w).matches()) {
                int n = Integer.parseInt(w);
                if (n < 1 || n > 50 || NOT_A_COUNT_BEFORE.contains(prev) || MEASURE_AFTER.contains(next)) continue;
                if (!options.isEmpty() && Pattern.compile("(?<!\\d)" + n + "(?!\\d)").matcher(options).find()) continue;   // a value of the product's own options (a size, a storage)
                if (tokens.size() <= 2 || UNITS.contains(next) || UNITS.contains(prev) || REQUEST.contains(prev)) found = n;
            }
        }
        return Optional.ofNullable(found);
    }
}
