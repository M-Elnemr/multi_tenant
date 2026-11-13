package com.platform.chat;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Replies that need no model: a bare hello at the start of a chat and a bare thank-you. Nothing that touches prices, stock, orders or policies. */
final class FastAnswers {
    private FastAnswers() {}

    private static final Set<String> HELLO = Set.of("hi", "hello", "hey", "hii", "good morning", "good evening", "مرحبا", "مرحبا بك", "هلا", "اهلا", "اهلا وسهلا", "هاي", "السلام عليكم", "سلام عليكم", "السلام عليكم ورحمه الله", "صباح الخير", "مساء الخير", "ازيك", "ازيكم", "هلو");
    private static final Set<String> THANKS = Set.of("thanks", "thank you", "thx", "شكرا", "شكرا ليك", "شكرا لك", "شكرا جدا", "متشكر", "متشكره", "تسلم", "تسلمي", "مشكور", "ميرسي", "الف شكر");

    static String norm(String raw) {
        return raw.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "").toLowerCase(Locale.ROOT).replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    /** True when the text has emoji or symbols and no letter or digit at all. */
    static boolean emojiOnly(String text) {
        boolean symbol = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) return false;
            int type = Character.getType(cp);
            if (type == Character.OTHER_SYMBOL || type == Character.MODIFIER_SYMBOL || type == Character.SURROGATE || cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF) || cp == 0x2764) symbol = true;
        }
        return symbol;
    }

    private static boolean arabic(String s) { return s.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF); }

    /** @param firstMessage true when the customer has not been answered yet in this chat. */
    static Optional<String> answer(String text, boolean firstMessage, String shopGreeting) {
        if (text == null || text.length() > 40) return Optional.empty();
        String n = norm(text);
        if (n.isEmpty()) {   // only emoji («❤️❤️», «👍»): a warm line from code, never a model's guess at what it meant
            if (!emojiOnly(text)) return Optional.empty();
            if (firstMessage && shopGreeting != null && !shopGreeting.isBlank()) return Optional.of(shopGreeting.trim());
            return Optional.of("❤️ تحت أمرك يا فندم، لو محتاج أي حاجة أنا موجود 🌸 / Anytime! Tell me if you need anything.");
        }
        boolean ar = arabic(text);
        if (firstMessage && HELLO.contains(n)) {
            if (shopGreeting != null && !shopGreeting.isBlank()) return Optional.of(shopGreeting.trim());
            return Optional.of(ar ? "أهلاً بحضرتك 🌸 تحب أساعدك في إيه؟" : "Hello! 🌸 What can I help you find today?");
        }
        if (!firstMessage && THANKS.contains(n)) return Optional.of(ar ? "العفو يا فندم 🌸 لو احتجت أي حاجة أنا موجود." : "You're welcome! 🌸 I'm here if you need anything else.");
        return Optional.empty();
    }
}
