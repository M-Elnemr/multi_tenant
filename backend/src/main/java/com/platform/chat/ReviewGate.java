package com.platform.chat;

import java.util.regex.Pattern;

/**
 * Decides, without a model, whether a draft reply still needs the second look of {@link ReplyReview} (app.ai.review.mode=conditional). Only a plain draft whose every figure is in the
 * facts the model was given, with nothing the reviewer exists to catch (a promise, a discount, a guarantee, an urgency, a picture, a personal address form, a long or two-part answer),
 * skips it. Everything else is reviewed as before. The money, claims and fact checks run on the sent text in either case; this only decides about the model call.
 */
final class ReviewGate {
    private ReviewGate() {}

    /** Words and forms the reviewer's rules 2, 4 and 6 are about: promises, offers, urgency, origin or quality claims, pictures, returns, how the customer is addressed. */
    private static final Pattern RISKY = Pattern.compile("ضمان|كفالة|كفاله|خصم|تخفيض|عرض|هدي[ةه]|مجان|اخر\\s+(?:قطع|حاج)|آخر\\s+(?:قطع|حاج)|بسرعة|الحق|صور[ةه]?|اصلي|أصلي|مستورد|100\\s*%|١٠٠\\s*٪|هتصل|هنتصل|هكلمك|مرتجع|استرجاع|استبدال|ترجيع|أستاذ|استاذ|يا\\s+(?:باشا|معلم|عم|ميدو|راجل|هانم|فندم\\s+فندم)|واتساب|whatsapp|guarantee|warranty|discount|free|original|imported|photo|picture|hurry|last\\s+piece", Pattern.CASE_INSENSITIVE);

    /** Null when the draft must be reviewed; otherwise why it may skip the review (a short code for the trace). */
    static String skipReason(String draft, String shown) {
        if (draft == null || draft.isBlank()) return null;
        if (draft.length() > 700) return null;
        if (draft.contains("⸻")) return null;
        if (RISKY.matcher(draft).find()) return null;
        if (ReplyReview.introducesNewNumbers(draft, shown)) return null;
        return "grounded_plain";
    }
}
