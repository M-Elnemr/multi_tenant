package com.platform.medical;

import java.security.SecureRandom;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Platform-wide patient numbers: random, 6 digits to start with. When more than 70 % of a length is used the next length (up to 9 digits) takes over. */
public final class PatientIds {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int START_DIGITS = 6, MAX_DIGITS = 9;

    private PatientIds() {}

    public static int allocate(JdbcClient jdbc) {
        long used = jdbc.sql("SELECT count(*) FROM medical.patient_accounts").query(Long.class).single();
        int digits = START_DIGITS;
        while (digits < MAX_DIGITS && used > 0.7 * (9.0 * Math.pow(10, digits - 1))) digits++;
        long low = (long) Math.pow(10, digits - 1), span = 9 * low;
        for (int i = 0; i < 50; i++) {
            int n = (int) (low + (long) (RANDOM.nextDouble() * span));
            if (jdbc.sql("SELECT count(*) FROM medical.patient_accounts WHERE public_id = :n").param("n", n).query(Long.class).single() == 0) return n;
        }
        throw new IllegalStateException("Could not allocate a patient number");
    }

    /** "123456" / "٣٤٥٦٧٨" -> the number, or null when the text is not a patient number (6-9 digits). */
    public static Integer parse(String raw) {
        if (raw == null) return null;
        String d = com.platform.shared.PhoneNormalizer.latinDigits(raw).trim().replaceAll("[\\s\\-]", "");
        return d.matches("\\d{6,9}") ? Integer.valueOf(d) : null;
    }
}
