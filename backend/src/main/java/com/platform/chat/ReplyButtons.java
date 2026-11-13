package com.platform.chat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Quick-action buttons under a reply, chosen by code from what the reply asks (never by the model, so they cost nothing and cannot invent anything): «تمام» / «تعديل الطلب» / «إلغاء الطلب» under an
 * order summary, the payment methods when only the payment is asked, and «أيوه» / «لا شكراً» under a plain yes-or-no question. A button carries a short label and the text that is sent as the
 * customer's own message when it is tapped, so every button works exactly like typing that text.
 */
public final class ReplyButtons {
    private ReplyButtons() {}

    /** @param label what the button shows (up to 20 characters, the WhatsApp limit); @param value the message sent when it is tapped. */
    public record Button(String label, String value) {}

    private static final int MAX_BODY = 1000;   // WhatsApp's interactive body is limited to 1024 characters

    private static final Pattern SUMMARY_ASK = Pattern.compile("(?:اكتب|اضغط|اكتبي)\\s*[«\"]?\\s*تمام|(?:أ|ا)?أكد\\s+(?:ال)?(?:أوردر|اوردر|طلب)|confirm\\s+(?:the\\s+|your\\s+)?order|type\\s+[«\"]?yes", Pattern.CASE_INSENSITIVE);
    private static final Pattern DETAILS_ASK = Pattern.compile("(?:اسم|اسمك|عنوان|العنوان|المحافظه|المحافظة|رقم|واتساب|الشارع|المنطقه|المنطقة|name|address|phone|number|street)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYMENT_ASK = Pattern.compile("(?:الدفع|ادفع|تدفع|payment|pay\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COD = Pattern.compile("(?:كاش|نقد|عند\\s+الاستلام|عند\\s+التوصيل|cash)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD = Pattern.compile("(?:فيزا|كارت|بطاق|اونلاين|أونلاين|visa|card|online)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YES_NO_VERB = Pattern.compile("(?:تحب|تحبي|تحبو|عايز|عايزه|عايزة|تؤكد|أأكد|اأكد|أحجز|احجز|أجهز|اجهز|أكمل|اكمل|أضيف|اضيف|أبعت|ابعت|أعرض|اعرض|أوريك|اوريك|ينفع|مناسب|would\\s+you\\s+like|shall\\s+i|do\\s+you\\s+want|want\\s+me\\s+to|is\\s+that\\s+ok)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHOICE = Pattern.compile("(?:أنهي|انهي|ايه|إيه|ايش|كام|مين|فين|امتى|إمتى|ازاي|إزاي|(?<!\\p{L})(?:ولا|أو|او)\\s|(?<!\\p{L})(?:which|what|how|when|where|who|or)(?!\\p{L}))", Pattern.CASE_INSENSITIVE);

    /** The buttons for a reply, empty when it asks nothing a button can answer. */
    public static List<Button> pick(String answer) {
        if (answer == null || answer.isBlank() || answer.length() > MAX_BODY) return List.of();
        boolean ar = answer.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF);
        String last = lastParagraph(answer);
        boolean asks = last.contains("؟") || last.contains("?");
        // 1. the order summary: confirm, change or cancel
        if (SUMMARY_ASK.matcher(answer).find() && asks)
            return ar ? List.of(new Button("تمام ✅", "تمام"), new Button("تعديل الطلب", "عايز أعدل الطلب"), new Button("إلغاء الطلب", "عايز ألغي الطلب"))
                      : List.of(new Button("Yes, confirm ✅", "yes"), new Button("Edit order", "I want to change the order"), new Button("Cancel order", "I want to cancel the order"));
        if (!asks) return List.of();
        // 2. only the payment method is asked: one button per method named
        if (PAYMENT_ASK.matcher(last).find() && !DETAILS_ASK.matcher(answer).find()) {
            List<Button> pay = new java.util.ArrayList<>();
            if (COD.matcher(last).find()) pay.add(ar ? new Button("دفع عند الاستلام", "كاش عند الاستلام") : new Button("Cash on delivery", "cash on delivery"));
            if (CARD.matcher(last).find()) pay.add(ar ? new Button("دفع بالكارت", "هدفع بالكارت") : new Button("Pay by card", "I will pay by card"));
            if (!pay.isEmpty()) return pay;
        }
        // 3. a plain yes-or-no question
        String q = lastSentence(last);
        if (YES_NO_VERB.matcher(q).find() && !CHOICE.matcher(q).find() && !DETAILS_ASK.matcher(q).find())
            return ar ? List.of(new Button("أيوه", "أيوه"), new Button("لا شكراً", "لا شكراً")) : List.of(new Button("Yes", "yes"), new Button("No thanks", "no thanks"));
        return List.of();
    }

    private static String lastParagraph(String text) {
        String t = text.strip();
        int cut = t.lastIndexOf("\n\n");
        return cut >= 0 ? t.substring(cut + 2) : t;
    }

    /** The sentence that holds the final question mark. */
    private static String lastSentence(String paragraph) {
        String t = paragraph.strip();
        int end = Math.max(t.lastIndexOf('؟'), t.lastIndexOf('?'));
        if (end < 0) return t;
        String head = t.substring(0, end);
        int start = Math.max(Math.max(head.lastIndexOf('.'), head.lastIndexOf('\n')), Math.max(head.lastIndexOf('؟'), Math.max(head.lastIndexOf('?'), head.lastIndexOf('!'))));
        return head.substring(start + 1);
    }

    /** The buttons as the maps the website chat shows. */
    public static List<java.util.Map<String, String>> asMaps(List<Button> buttons) {
        return buttons.stream().map(b -> java.util.Map.of("label", b.label(), "value", b.value())).toList();
    }
}
