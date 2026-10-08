package com.platform.shared;

/** Normalizes phone numbers to E.164. Egypt (+20) is the default country. */
public final class PhoneNormalizer {
    private PhoneNormalizer() {}

    /** Arabic-Indic (٠-٩) and Persian (۰-۹) digits to 0-9, so a number typed on an Arabic keyboard is the same number. */
    public static String latinDigits(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '\u0660' && c <= '\u0669') b.append((char) ('0' + (c - '\u0660')));
            else if (c >= '\u06F0' && c <= '\u06F9') b.append((char) ('0' + (c - '\u06F0')));
            else b.append(c);
        }
        return b.toString();
    }

    /** Emails are English letters only: trimmed, lower-case, null when blank; anything non-ASCII or without a plausible shape is a 400. */
    public static String cleanEmail(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String e = latinDigits(raw.trim()).toLowerCase();
        if (!e.matches("[\\x21-\\x7E]+@[\\x21-\\x7E]+\\.[\\x21-\\x7E]+") || e.chars().anyMatch(ch -> ch > 126))
            throw BusinessException.badRequest("INVALID_EMAIL", "The email must use English letters only, like name@example.com");
        return e;
    }

    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = latinDigits(raw).trim().replaceAll("[\\s\\-().]", "");
        if (s.isEmpty()) return null;
        if (s.startsWith("00")) s = "+" + s.substring(2);
        if (!s.startsWith("+")) {
            if (s.startsWith("0")) s = "+20" + s.substring(1);       // 01012345678 -> +201012345678
            else if (s.startsWith("20")) s = "+" + s;                // 201012345678
            else s = "+20" + s;                                      // 1012345678
        }
        if (!s.matches("\\+[1-9][0-9]{7,14}")) {
            throw BusinessException.badRequest("INVALID_PHONE", "Invalid phone number");
        }
        return s;
    }

    public static boolean looksLikeEmail(String s) {
        return s != null && s.contains("@");
    }
}
