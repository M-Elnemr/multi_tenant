package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tells a model PROVIDER's own failure apart from a normal answer, so a failure never reaches a customer as if the salesperson had written it.
 * Some providers (seen with GLM on Hive) answer HTTP 200 with a capacity message as the content («high traffic, try again in a minute»), or with an error object, or
 * with a finish reason of "error". Detection is two layers, structure first: (1) the response's own error fields; (2) only if the content is short, has no tool call
 * and no figures, a text match that needs BOTH a service subject and a busy/unavailable state (or an explicit retry-later instruction aimed at the customer).
 * A refusal or a salesperson's «wait a moment while I check» is not an outage. A false positive costs one extra call on the other provider, never a wrong answer.
 */
final class ProviderFailure {
    private ProviderFailure() {}

    /** Failure categories used in logs, events and traces. */
    static final String RATE_LIMIT = "RATE_LIMIT", SERVER_ERROR = "SERVER_ERROR", OVERLOAD_MESSAGE = "OVERLOAD_MESSAGE", PROVIDER_ERROR = "PROVIDER_ERROR", EMPTY = "EMPTY",
            MALFORMED = "MALFORMED", TIMEOUT = "TIMEOUT", CONNECTION = "CONNECTION", REJECTED = "REJECTED";

    /** The category of an HTTP status that means «the provider could not serve this request now». */
    static String ofStatus(int status) {
        if (status == 429) return RATE_LIMIT;
        if (status == 408) return TIMEOUT;
        if (status / 100 == 5) return SERVER_ERROR;
        if (status == 401 || status == 403) return REJECTED;
        return PROVIDER_ERROR;
    }

    private static final List<String> BAD_FINISH = List.of("error", "insufficient_system_resource", "network_error", "model_overloaded", "overloaded");

    /** A description when the (HTTP 200) body itself reports a provider failure, else null: an error object or string, a failing finish reason, or no choices at all. */
    static String inBody(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) return "empty body";
        JsonNode err = root.get("error");
        if (err != null && !err.isNull() && !(err.isTextual() && err.asText().isBlank())) {
            String m = err.isObject() ? err.path("message").asText(err.path("code").asText("error")) : err.asText();
            return "error field: " + clip(m);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            String m = root.path("msg").asText(root.path("message").asText(""));   // {"code":1305,"msg":"...","success":false} style
            return m.isBlank() ? "no choices" : "no choices: " + clip(m);
        }
        String finish = choices.path(0).path("finish_reason").asText("");
        if (BAD_FINISH.contains(finish.toLowerCase(java.util.Locale.ROOT))) return "finish_reason " + finish;
        return null;
    }

    private static String clip(String s) { return s == null ? "" : s.length() > 120 ? s.substring(0, 120) : s; }

    // ---- text ------------------------------------------------------------------------------------------------------------------

    private static final int MAX_LEN = 350;

    private static final Pattern EN_STRONG = Pattern.compile(
            "too many requests|rate[- ]?limit|(high|heavy|unusual(ly high)?) (traffic|volume|demand|load)|at capacity|over ?capacity|overloaded|"
          + "(service|server|servers|system|model|api|platform)( is| are| seems| currently| right now| very| too)* (busy|congested|unavailable|down|under (heavy )?load|experiencing)|"
          + "temporarily unavailable|experiencing (a )?(high|heavy|unusually)");
    /** An instruction to the customer to come back later: only counts together with a service subject, or when the whole message is that instruction. */
    private static final Pattern EN_RETRY = Pattern.compile("(please )?(wait|try again|retry|come back|check back)( again)? (in|after|for) (a|one|1|two|2|few|\\d+) ?(minutes?|moments?|seconds?|mins?)|try again (later|shortly|soon)|please try again");
    private static final Pattern EN_SUBJECT = Pattern.compile("\\b(server|servers|service|system|network|traffic|requests|capacity)\\b");

    // Arabic is matched on FastAnswers.norm text (no tashkeel, alef/ya/ta marbuta folded, punctuation removed)
    private static final String AR_SUBJECT = "(السيرفر|السيرفرات|الخادم|الخوادم|السرفر|النظام|الخدمه|المنصه|الموقع|الشبكه|الذكاء الاصطناعي)";
    private static final String AR_BUSY = "(مشغول\\S*|مضغوط\\S*|ضغط|زحمه|ازدحام|محمل\\S*|حمل (عالي|كبير|زايد)|غير متاح\\S*|متوقف\\S*|معطل\\S*|واقع|وقع|مش شغال\\S*|بطيء|عدد كبير من الطلبات|كثره (ال)?(طلبات|استخدام))";
    private static final Pattern AR_SERVICE_BUSY = Pattern.compile(AR_SUBJECT + "( \\S+){0,5} " + AR_BUSY + "|" + AR_BUSY + "( \\S+){0,4} (علي|على|في|ع) " + AR_SUBJECT);
    private static final Pattern AR_RETRY = Pattern.compile("(حاول|جرب|ارجع|اعد|ابعت|كلمنا|راجعنا)\\S*( \\S+){0,3} (تاني|مره تانيه|مجددا|لاحقا|بعد قليل|بعد شويه|بعد دقيقه|بعد دقيقتين|بعد لحظات)");
    private static final Pattern AR_STRONG = Pattern.compile("(هناك|فيه|في|يوجد) (ضغط|زحمه|ازدحام) (عالي|كبير|شديد|كبير جدا)( علي| على| ع)? ?(النظام|الخدمه|السيرفر|الخادم)|الخدمه (غير متاحه|متوقفه) (حاليا|مؤقتا)|تجاوزت (الحد|عدد الطلبات)");

    private static final Pattern ZH = Pattern.compile("(访问量|流量|请求|负载).{0,6}(过大|过多|过高|繁忙|太多)|服务.{0,4}(繁忙|不可用|暂时)|系统繁忙|请稍后(再)?试|稍后重试|限流");

    /** The category (OVERLOAD_MESSAGE) when {@code text} reads like the provider's own capacity/unavailability notice, else null. Called only for an answer with no tool call. */
    static String overloadMessage(String text) {
        if (text == null) return null;
        String t = text.strip();
        if (t.isEmpty() || t.length() > MAX_LEN) return null;
        if (ZH.matcher(t).find()) return OVERLOAD_MESSAGE;
        String en = t.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        if (EN_STRONG.matcher(en).find()) return OVERLOAD_MESSAGE;
        boolean shortNote = t.length() <= 200 && !t.matches("(?s).*\\d{2,}.*");   // a retry-later line is brief and carries no price, quantity or order number
        if (shortNote && EN_RETRY.matcher(en).find() && (EN_SUBJECT.matcher(en).find() || en.length() <= 120 && !en.contains("let me") && !en.contains("while i"))) return OVERLOAD_MESSAGE;
        String ar = FastAnswers.norm(t);
        if (AR_STRONG.matcher(ar).find() || AR_SERVICE_BUSY.matcher(ar).find()) return OVERLOAD_MESSAGE;
        if (shortNote && AR_RETRY.matcher(ar).find() && (ar.length() <= 90 || ar.matches(".*" + AR_SUBJECT + ".*"))) return OVERLOAD_MESSAGE;
        return null;
    }
}
