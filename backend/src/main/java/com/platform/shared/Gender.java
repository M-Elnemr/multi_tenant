package com.platform.shared;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How to speak to a customer in Arabic: «أهلاً بيك» to a man, «أهلاً بيكي» to a woman. Unknown is treated as masculine, the written default.
 * Texts can carry pairs {masculine|feminine}; {@link #apply} picks one side.
 */
public enum Gender {
    MALE, FEMALE, UNKNOWN;

    private static final Pattern PAIR = Pattern.compile("\\{([^{}|]{1,40})\\|([^{}|]{1,40})\\}");

    public static Gender of(String s) { return "FEMALE".equalsIgnoreCase(s) ? FEMALE : "MALE".equalsIgnoreCase(s) ? MALE : UNKNOWN; }

    /** The masculine or the feminine form. */
    public String pick(String male, String female) { return this == FEMALE ? female : male; }

    /** Replaces every {masculine|feminine} pair with the right side. Other braces such as {name} are left for the caller. */
    public String apply(String text) {
        if (text == null || text.indexOf('|') < 0) return text;
        Matcher m = PAIR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(this == FEMALE ? m.group(2) : m.group(1)));
        m.appendTail(out);
        return out.toString();
    }
}
