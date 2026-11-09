package com.platform.chat.unofficial;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "behave like a person sending by hand" rules, pure and deterministic given a {@link Random}, so they can be unit tested. One instance per policy profile:
 * the Messenger / Instagram manual-style route ({@code app.meta.unofficial.*}, strict) and the WhatsApp own-number route ({@code app.broadcast.whatsapp.*}, the owner's
 * 5-15 s gap and no hard cap). A cap of 0 means "off". Nothing here talks to a network or a database.
 *
 * Honest limit: these rules lower the chance of an account being flagged; they cannot make an unofficial route safe. Verified only against fakes.
 */
public final class HumanPolicy {
    /** All the knobs of one profile. */
    public record Profile(String name, boolean logNormal, int gapMinSeconds, int gapMaxSeconds, int jitterMaxSeconds,
                          int breakEveryMin, int breakEveryMax, int breakMinSeconds, int breakMaxSeconds,
                          int hourlyCap, int maxDaily, int[] warmupCaps,
                          int activeFromHour, int activeToHour,
                          int maxChars, int maxLinks, int maxEmojiRepeat, int minVariants,
                          int typingMsPerChar, int typingBaseMinMs, int typingBaseMaxMs, int typingCapMs,
                          int ownerReplyMinutes, int maxContactAgeDays, int sameContactDays, int minMessages) {}

    /** Messenger / Instagram manual style: 40-120 s log-normal gap + jitter, break of 4-12 min every 8-15 messages, 25 per hour, warm-up ramp to 80 a day, 09:00-21:00. */
    public static Profile manualDefaults() {
        return new Profile("manual-style", true, 40, 120, 15, 8, 15, 4 * 60, 12 * 60, 25, 80, new int[] {10, 15, 20, 30, 40, 50}, 9, 21,
                500, 1, 3, 3, 40, 2000, 6000, 0, 10, 180, 30, 2);
    }

    /** WhatsApp own number: the owner's random 5-15 s gap, NO hourly or daily cap, a 1-3 min break every 40-80 messages, no active-hours window (quiet hours are separate). */
    public static Profile whatsappDefaults() {
        return new Profile("whatsapp", false, 5, 15, 0, 40, 80, 60, 180, 0, 0, new int[0], 0, 0,
                1000, 1, 3, 0, 40, 0, 0, 4000, 10, 0, 0, 0);
    }

    private final Profile p;

    public HumanPolicy(Profile profile) { this.p = profile; }

    public Profile profile() { return p; }

    // ---- rhythm ---------------------------------------------------------------------------------------------------------------

    /** The wait after one message, in milliseconds: between min and max (log-normal around the geometric mean when the profile says so, uniform otherwise) plus a random jitter. */
    public long gapMillis(Random r) { return gapMillis(r, p.gapMinSeconds(), p.gapMaxSeconds()); }

    public long gapMillis(Random r, int minSeconds, int maxSeconds) {
        int lo = Math.max(0, minSeconds), hi = Math.max(lo, maxSeconds);
        double seconds;
        if (hi == lo) seconds = lo;
        else if (p.logNormal()) {
            double median = Math.sqrt(Math.max(1, lo) * (double) Math.max(1, hi));
            double v = Math.exp(Math.log(median) + 0.35 * r.nextGaussian());   // many gaps near the middle, a few short, a few long; never a fixed rhythm
            seconds = Math.min(hi, Math.max(lo, v));
        } else seconds = lo + r.nextDouble() * (hi - lo);
        double jitter = p.jitterMaxSeconds() <= 0 ? 0 : r.nextDouble() * p.jitterMaxSeconds();
        return Math.round((seconds + jitter) * 1000);
    }

    /** After how many messages the next longer break comes (random inside the profile's range). */
    public int nextBreakAfter(Random r) {
        int lo = Math.max(1, p.breakEveryMin()), hi = Math.max(lo, p.breakEveryMax());
        return lo + r.nextInt(hi - lo + 1);
    }

    /** How long that break lasts, in milliseconds. */
    public long breakMillis(Random r) {
        int lo = Math.max(0, p.breakMinSeconds()), hi = Math.max(lo, p.breakMaxSeconds());
        return (lo + (long) (r.nextDouble() * (hi - lo))) * 1000;
    }

    // ---- caps -----------------------------------------------------------------------------------------------------------------

    /** Day 1 is the day the account was connected; null counts as day 1. */
    public static int warmupDay(LocalDate started, LocalDate today) {
        return started == null ? 1 : (int) Math.max(0, ChronoUnit.DAYS.between(started, today)) + 1;
    }

    /** Messages allowed on that warm-up day; 0 = no daily cap. Days past the ramp get {@code maxDaily}. */
    public int dailyCap(int day) {
        int[] ramp = p.warmupCaps() == null ? new int[0] : p.warmupCaps();
        if (p.maxDaily() <= 0 && ramp.length == 0) return 0;
        int cap = day >= 1 && day <= ramp.length ? ramp[day - 1] : p.maxDaily() > 0 ? p.maxDaily() : ramp[ramp.length - 1];
        return p.maxDaily() > 0 ? Math.min(cap, p.maxDaily()) : cap;
    }

    public boolean hourlyAllows(long sentInLastHour) { return p.hourlyCap() <= 0 || sentInLastHour < p.hourlyCap(); }

    public boolean dailyAllows(int day, long sentToday) { int cap = dailyCap(day); return cap <= 0 || sentToday < cap; }

    /** True when a message may go out at this moment of the shop's day (09:00-21:00 for manual style; from = to switches the window off). */
    public boolean inActiveWindow(ZonedDateTime local) {
        int from = p.activeFromHour(), to = p.activeToHour(), h = local.getHour();
        if (from == to) return true;
        return from < to ? h >= from && h < to : h >= from || h < to;
    }

    /** How many days a list of that size needs, from the given warm-up day, honouring the daily cap, the hourly cap and the gaps. Used for the owner's estimate. */
    public double estimateDays(long messages, int startDay, long alreadySentToday) {
        if (messages <= 0) return 0;
        long left = messages;
        double days = 0;
        long avgGap = Math.round((p.gapMinSeconds() + p.gapMaxSeconds()) / 2.0 + p.jitterMaxSeconds() / 2.0);
        int window = p.activeFromHour() == p.activeToHour() ? 24 : ((p.activeToHour() - p.activeFromHour() + 24) % 24);
        long byTime = Math.max(1, (window * 3600L) / Math.max(1, avgGap));
        for (int d = startDay, guard = 0; left > 0 && guard < 400; d++, guard++) {
            long cap = dailyCap(d) <= 0 ? Long.MAX_VALUE : dailyCap(d);
            if (d == startDay) cap = Math.max(0, cap - alreadySentToday);
            if (p.hourlyCap() > 0) cap = Math.min(cap, (long) p.hourlyCap() * window);
            cap = Math.min(cap, byTime);
            long today = Math.min(left, cap);
            left -= today;
            days += today == 0 ? 1 : (left > 0 ? 1 : (double) today / Math.max(1, cap));
        }
        return days;
    }

    // ---- text -----------------------------------------------------------------------------------------------------------------

    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s]+");

    /** Keeps the first copy of every link and drops the repeats (the same link twice looks like spam). */
    public static String stripRepeatedLinks(String text) {
        Matcher m = LINK.matcher(text);
        Set<String> seen = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, seen.add(m.group().toLowerCase()) ? Matcher.quoteReplacement(m.group()) : "");
        m.appendTail(sb);
        return sb.toString().replaceAll("[ \\t]{2,}", " ").replaceAll("\\s+\\n", "\n").trim();
    }

    static int links(String text) {
        Matcher m = LINK.matcher(text);
        Set<String> seen = new HashSet<>();
        while (m.find()) seen.add(m.group().toLowerCase());
        return seen.size();
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x1F000 && cp <= 0x1F2FF) || cp == 0x2B50;
    }

    /** Null when the text may be sent, otherwise why not: EMPTY, TOO_LONG, TOO_MANY_LINKS, ALL_CAPS, EMOJI_REPEAT. */
    public String contentProblem(String text) {
        if (text == null || text.isBlank()) return "EMPTY";
        if (text.length() > p.maxChars()) return "TOO_LONG";
        if (links(text) > p.maxLinks()) return "TOO_MANY_LINKS";
        String bare = LINK.matcher(text).replaceAll(" ");
        long upper = bare.chars().filter(c -> c < 128 && Character.isUpperCase(c)).count(), letters = bare.chars().filter(c -> c < 128 && Character.isLetter(c)).count();
        if (letters >= 10 && upper * 100 > letters * 60) return "ALL_CAPS";
        if (bare.matches("(?s).*[!?]{4,}.*")) return "ALL_CAPS";
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isEmoji(cp) && counts.merge(cp, 1, Integer::sum) > p.maxEmojiRepeat()) return "EMOJI_REPEAT";
        }
        return null;
    }

    /** True when the main text plus the other wordings give at least the required number of different versions (0 required = always true). */
    public boolean enoughVariants(String main, List<String> variants) {
        if (p.minVariants() <= 0) return true;
        Set<String> all = new HashSet<>();
        if (main != null && !main.isBlank()) all.add(main.trim());
        if (variants != null) for (String v : variants) if (v != null && !v.isBlank()) all.add(v.trim());
        return all.size() >= p.minVariants();
    }

    /** Round-robin over main + variants: two consecutive recipients never get the same wording (with two or more wordings). */
    public static String rotate(String main, List<String> variants, int seq) {
        if (variants == null || variants.isEmpty()) return main;
        int i = Math.floorMod(seq, variants.size() + 1);
        return i == 0 ? main : variants.get(i - 1);
    }

    /**
     * Tiny safe randomization that never changes the meaning: a space (or none) before a trailing emoji, a final full stop kept or dropped on plain sentences.
     * Links, numbers, questions and exclamations are never touched. {@code avoidHash} is the hash of the last message sent: more attempts are made so this one differs when possible.
     */
    public String vary(String text, Random r, String avoidHash) {
        String best = text;
        for (int attempt = 0; attempt < 8; attempt++) {
            best = varyOnce(text, r);
            if (avoidHash == null || !hash(best).equals(avoidHash)) return best;
        }
        return best;
    }

    private static String varyOnce(String text, Random r) {
        String t = text.stripTrailing();
        if (t.isEmpty()) return text;
        int last = t.codePointBefore(t.length());
        if (isEmoji(last)) {
            int n = Character.charCount(last);
            String head = t.substring(0, t.length() - n).stripTrailing();
            return head + (r.nextBoolean() ? " " : "") + t.substring(t.length() - n);
        }
        boolean endsLink = LINK.matcher(t).results().anyMatch(m -> m.end() == t.length());
        char c = t.charAt(t.length() - 1);
        if (!endsLink && c == '.' && !t.endsWith("..") && r.nextBoolean()) return t.substring(0, t.length() - 1);
        if (!endsLink && Character.isLetter(c) && r.nextInt(3) == 0) return t + ".";
        return t;
    }

    public static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** How long to "type" before sending: a random base plus per-character time, capped by the profile, and never more than {@code limitMs} when given (0 = no limit). */
    public int typingMillis(int chars, Random r, long limitMs) {
        long base = p.typingBaseMaxMs() > p.typingBaseMinMs() ? p.typingBaseMinMs() + r.nextInt(p.typingBaseMaxMs() - p.typingBaseMinMs() + 1) : p.typingBaseMinMs();
        long ms = base + (long) chars * p.typingMsPerChar();
        if (p.typingCapMs() > 0) ms = Math.min(ms, p.typingCapMs());
        if (limitMs > 0) ms = Math.min(ms, limitMs);
        return (int) Math.max(0, ms);
    }
}
