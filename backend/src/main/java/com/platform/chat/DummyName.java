package com.platform.chat;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Does a Messenger / Instagram profile name look like a real person's name, or like a handle the AI should not call the customer by («x7k_29», «user12345», «🌸🌸», «Instagram User»)?
 * When it is a dummy, the AI asks for the customer's name at the start of the chat, like a real salesperson would.
 */
public final class DummyName {
    private static final Set<String> GENERIC = Set.of("user", "facebook user", "instagram user", "messenger user", "unknown", "guest", "new user", "test", "admin", "مستخدم", "مستخدم فيسبوك", "زائر");
    private static final Pattern REPEATS = Pattern.compile("(.)\\1{3,}");
    private static final Pattern LETTERS = Pattern.compile("[\\p{IsArabic}\\p{IsLatin}]");

    private DummyName() {}

    public static boolean check(String raw) {
        if (raw == null) return true;
        String s = raw.trim();
        if (s.isEmpty()) return true;
        String lower = s.toLowerCase(Locale.ROOT);
        if (GENERIC.contains(lower) || lower.startsWith("user") && lower.substring(4).matches("[\\d_ .-]*")) return true;
        long letters = s.codePoints().filter(cp -> LETTERS.matcher(new String(Character.toChars(cp))).matches()).count();
        if (letters < 2) return true;                                                  // emoji / symbols only, or one letter
        if (s.matches(".*\\d.*") || s.contains("_") || s.contains("@")) return true;    // handles: x7k_29, mona.22, @shop
        if (s.length() > 32) return true;                                               // a long handle or a slogan
        if (REPEATS.matcher(lower).find()) return true;                                 // «hhhhh», «ممممم»
        return ChatService.firstName(s) == null;                                         // «shop», «official», «ماما» are not a person's first name («🌸 Mona Ali 🌸» is)
    }
}
