package com.platform.chat;

import java.util.regex.Pattern;

/**
 * Decides, without a model, whether a draft reply still needs the second look of {@link ReplyReview} (app.ai.review.mode=conditional). Only a plain draft whose every figure is in the
 * facts the model was given, with nothing the reviewer exists to catch (a promise, a discount, a guarantee, an urgency, a picture, a personal address form, a long or two-part answer),
 * skips it. Everything else is reviewed as before. The money, claims and fact checks run on the sent text in either case; this only decides about the model call.
 */
final class ReviewGate {
    private ReviewGate() {}

    /** Words and forms the reviewer's rules are about: promises, offers, urgency, origin or quality claims, pictures, returns, how the customer is addressed, and (see below) denials, store policy, delivery and order state. */
    private static final Pattern RISKY = Pattern.compile("ضمان|كفالة|كفاله|خصم|تخفيض|عرض|هدي[ةه]|مجان|اخر\\s+(?:قطع|حاج)|آخر\\s+(?:قطع|حاج)|بسرعة|الحق|صور[ةه]?|اصلي|أصلي|مستورد|100\\s*%|١٠٠\\s*٪|هتصل|هنتصل|هكلمك|مرتجع|استرجاع|استبدال|ترجيع|أستاذ|استاذ|يا\\s+(?:باشا|معلم|عم|ميدو|راجل|هانم|فندم\\s+فندم)|واتساب|whatsapp|guarantee|warranty|discount|free|original|imported|photo|picture|hurry|last\\s+piece"
            // added after the A/B benchmark: what the customer relies on and the shop is held to. A denial or an out-of-stock claim, the minimum order and other store policy, a delivery fee or time, and
            // anything that says an order is summarised, recorded or confirmed always get the second look; the code checks (prices, variants, stock) run on the sent text either way.
            + "|مالناش|ملناش|مفيش|مش\\s+(?:موجود|متوفر|متاح|فيه|بييجي|بيجي|عندنا|بنبيع)|معندناش|ماعندناش|مبنبيعش|خلص|نفذ|غير\\s+متاح|لا\\s+يوجد|للأسف|للاسف|out\\s+of\\s+stock|not\\s+available|sold\\s+out|(?:don't|do\\s+not)\\s+have"
            + "|أقل\\s+(?:طلب|قيمة)|اقل\\s+(?:طلب|قيمة)|الحد\\s+الأدنى|الحد\\s+الادنى|minimum"
            + "|توصيل|شحن|delivery|shipping"
            + "|ملخص|سجلت|اتسجل|تسجيل|تأكيد|تاكيد|أأكد|اأكد|الأوردر|الاوردر|confirmed|your\\s+order"
            // added in the quality mission: other ways of denying or running out (a spelling the first list missed), delivery times, the last price, wholesale
            + "|نفد|غير\\s+(?:موجود|متوفر)|مش\\s+(?:لاقي|هينفع|هنقدر|هقدر)|لسه\\s+مجاش|يوصل|هيوصل|خلال\\s+[\\d٠-٩]|سعر\\s+(?:نهائي|الجمله|الجملة)|جمله|جملة|اخر\\s+سعر|آخر\\s+سعر|مقفول|مغلق|closed|unavailable|no\\s+stock|wholesale|final\\s+price|arrive|deliver"
            // the same ideas written in Latin letters (Arabizi)
            + "|\\b(?:ma3ndna[sc]h|ma3ndnash|mafe?[sc]h|mafi[sc]h|mfe?[sc]h|mfi[sc]h|msh\\s+(?:mawgod|mawgoud|mot[a]?wafer|met2?ah)|khalas(?:t|et)?|nafad(?:et)?|nefd(?:et)?|tawsee?l|shahn|ta7mil|khasm|dam[a]?an|garantee?|wa7da\\s+bas)\\b", Pattern.CASE_INSENSITIVE);

    /** Diacritics and stretching removed («مفيييييش», «للأَسف»), so a spelling variation never skips the review. */
    static String plain(String s) {
        return s.replaceAll("[\\u064B-\\u0652\\u0640]", "").replaceAll("(\\p{L})\\1{2,}", "$1");
    }

    /** Null when the draft must be reviewed; otherwise why it may skip the review (a short code for the trace). */
    static String skipReason(String draft, String shown) {
        if (draft == null || draft.isBlank()) return null;
        if (draft.length() > 700) return null;
        if (draft.contains("⸻")) return null;
        if (RISKY.matcher(draft).find() || RISKY.matcher(plain(draft)).find()) return null;
        if (ReplyReview.introducesNewNumbers(draft, shown)) return null;
        return "grounded_plain";
    }
}
