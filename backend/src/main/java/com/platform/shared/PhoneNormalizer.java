package com.platform.shared;

/** Normalizes phone numbers to E.164. Egypt (+20) is the default country. */
public final class PhoneNormalizer {
    private PhoneNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("[\\s\\-().]", "");
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
