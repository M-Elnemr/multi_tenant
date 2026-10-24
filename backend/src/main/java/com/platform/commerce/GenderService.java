package com.platform.commerce;

import com.platform.shared.Gender;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Knows each customer's gender. The customer's own choice (account profile) wins over the shop owner's, which wins over a guess from the first name. A guess is
 * only a guess: it is stored as such, is replaced as soon as the customer or the owner says otherwise, and when the name does not tell, the answer is UNKNOWN.
 */
@Service
public class GenderService {
    private final JdbcClient jdbc;

    public GenderService(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---- guessing from a first name ----------------------------------------------------------------------------------------------

    private static final Set<String> FEMALE = new java.util.HashSet<>(java.util.Arrays.asList("منى", "مني", "سارة", "ساره", "نور", "هدى", "هدي", "ندى", "ندي", "رنا", "دينا", "هبة", "هبه", "آية", "اية", "ايه", "أية", "مريم", "فاطمة", "فاطمه", "عائشة", "عايشة", "خديجة", "زينب", "نادية", "ناديه", "سلمى", "سلمي", "ليلى", "ليلي", "لينا", "جنى", "جني", "جنة", "ياسمين", "يسرا", "إيمان", "ايمان", "أميرة", "اميره", "اميرة", "رحمة", "رحمه", "حنان", "سعاد", "سهى", "سهي", "شيماء", "شيمة", "نهى", "نهي", "هالة", "هاله", "هند", "وفاء", "ولاء", "أسماء", "اسماء", "رانيا", "راندا", "ريم", "روان", "رغد", "غادة", "فرح", "لمى", "لمي", "لجين", "ملك", "مي", "ميار", "ميادة", "منال", "منار", "مروة", "مروه", "نجوى", "نسمة", "نسمه", "نيرة", "نيره", "نرمين", "نانسي", "هاجر", "هايدي", "هدير", "هيا", "ولاء", "يارا", "أروى", "اروى", "أحلام", "احلام", "بسمة", "بسمه", "بسنت", "تسنيم", "جميلة", "جميله", "حبيبة", "حبيبه", "حياة", "دعاء", "دلال", "رحاب", "رشا", "رضوى", "ريهام", "زهرة", "زهره", "سمر", "سمية", "سميه", "سناء", "سوسن", "شهد", "شروق", "صفاء", "عبير", "علا", "علياء", "عزة", "عزه", "غدير", "فاتن", "فريدة", "قمر", "كريمة", "كوثر", "لبنى", "لطيفة", "لولوة", "ماجدة", "مها", "مديحة", "ميرنا", "ميرفت", "نبيلة", "نجلاء", "نوال", "نوران", "نورهان", "هنا", "وردة", "ورد", "أمل", "امل", "أماني", "اماني", "آلاء", "الاء", "إسراء", "اسراء", "إنجي", "انجي", "ريتاج", "تالا", "جود", "لانا", "سندس", "لينة", "ريماس", "أسيل", "اسيل", "عهد", "ديما", "دانا", "شذى", "شذي", "رزان", "رهف", "هيفاء", "نوف", "العنود", "أريج", "اريج", "ريناد", "سلوى", "سلوي", "مشاعل", "منيرة", "موزة", "مريم", "شيخة", "لطيفه",
            "mona", "sara", "sarah", "nour", "noor", "hoda", "huda", "nada", "rana", "dina", "heba", "aya", "mariam", "maryam", "fatma", "fatima", "aisha", "khadija", "zeinab", "zainab", "salma", "laila", "layla", "lina", "jana", "yasmin", "yasmine", "iman", "eman", "amira", "rahma", "hanan", "shaimaa", "nahla", "hala", "hind", "wafaa", "walaa", "asmaa", "rania", "randa", "reem", "rawan", "ghada", "farah", "lamia", "malak", "mai", "may", "manal", "mona", "marwa", "nesma", "nirmeen", "nancy", "hagar", "hajar", "hadeer", "yara", "basma", "bassant", "tasneem", "habiba", "doaa", "rasha", "radwa", "reham", "samar", "sohaila", "shahd", "sherouk", "abeer", "alaa", "ghadeer", "fatin", "kawthar", "lobna", "majda", "maha", "mirna", "mervat", "nagwa", "nouran", "nourhan", "esraa", "engy", "angy", "tala", "lana", "sondos", "areej", "salwa", "dalia", "dalal", "hanin", "jumana", "rimas"));
    private static final Set<String> MALE_ENDING_A = new java.util.HashSet<>(java.util.Arrays.asList("حمزة", "حمزه", "أسامة", "اسامه", "اسامة", "معاوية", "طلحة", "طلحه", "عبيدة", "قتيبة", "عكاشة", "زكريا", "مصطفى", "مصطفي", "موسى", "موسي", "عيسى", "عيسي", "يحيى", "يحيي", "طه", "عمرو",
            "hamza", "osama", "usama", "mostafa", "mustafa", "moussa", "musa", "issa", "isa", "yahya", "taha", "zakaria", "zakariya", "talha", "obada", "ubada", "mo3awia", "muawia", "joshua", "luca", "elia", "nikita", "sasha", "saad", "ahmed"));

    /** Arabic is compared without diacritics and with the alef / yaa / taa-marbuta variants folded, so spelling differences do not matter. */
    static String fold(String s) {
        return s.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه').toLowerCase().trim();
    }

    private static final Set<String> FEMALE_FOLDED = new java.util.HashSet<>(), MALE_ENDING_A_FOLDED = new java.util.HashSet<>();
    static {
        for (String n : FEMALE) FEMALE_FOLDED.add(fold(n));
        for (String n : MALE_ENDING_A) MALE_ENDING_A_FOLDED.add(fold(n));
    }

    /** MALE, FEMALE, or null when the name does not tell. Looks at the first name only. */
    public static String guess(String fullName) {
        if (fullName == null) return null;
        String first = fullName.trim().split("\\s+")[0];
        if (first.length() < 2) return null;
        String f = fold(first);
        if (FEMALE_FOLDED.contains(f)) return "FEMALE";
        if (MALE_ENDING_A_FOLDED.contains(f)) return "MALE";
        boolean arabic = f.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF);
        if (arabic) {
            // Arabic names ending in taa marbuta (written ة / ه) are overwhelmingly feminine; the common masculine exceptions are listed above
            return f.length() >= 4 && f.endsWith("ه") ? "FEMALE" : null;   // other Arabic names: we do not claim to know, and unknown is written as the masculine default anyway
        }
        return f.length() >= 4 && f.endsWith("a") ? "FEMALE" : null;   // Latin spelling: a trailing "a" suggests a woman, anything else we do not claim to know
    }

    // ---- the stored gender -------------------------------------------------------------------------------------------------------

    private static String last9(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        return d.substring(Math.max(0, d.length() - 9));
    }

    /** The gender to use for this phone number in this shop. Fills in a guess from the name the first time it is needed. */
    public Gender forPhone(UUID tenantId, String phone) {
        var r = jdbc.sql("SELECT id, name, gender FROM commerce.customers WHERE tenant_id = :t AND right(regexp_replace(coalesce(phone, ''), '\\D', '', 'g'), 9) = :p ORDER BY created_at LIMIT 1")
                .param("t", tenantId).param("p", last9(phone)).query().listOfRows().stream().findFirst().orElse(null);
        if (r == null) return Gender.UNKNOWN;
        if (r.get("gender") != null) return Gender.of((String) r.get("gender"));
        String g = guess((String) r.get("name"));
        if (g != null) jdbc.sql("UPDATE commerce.customers SET gender = :g, gender_source = 'AI' WHERE id = :i AND gender IS NULL").param("g", g).param("i", r.get("id")).update();
        return Gender.of(g);
    }

    /** The gender for a name typed in a chat, when the customer is not known yet. */
    public static Gender forName(String name) { return Gender.of(guess(name)); }

    /** Called whenever a customer record is created or its name changes: the customer's own pick (account profile) wins, an owner's pick stays, a guess is refreshed. */
    public void refresh(UUID customerId, String name, UUID clientId) {
        String own = clientId == null ? null : jdbc.sql("SELECT gender FROM commerce.client_accounts WHERE id = :u").param("u", clientId).query(String.class).optional().orElse(null);
        if (own != null) { jdbc.sql("UPDATE commerce.customers SET gender = :g, gender_source = 'SELF' WHERE id = :i").param("g", own).param("i", customerId).update(); return; }
        String g = guess(name);
        jdbc.sql("UPDATE commerce.customers SET gender = CAST(:g AS varchar), gender_source = CASE WHEN CAST(:g AS varchar) IS NULL THEN NULL ELSE 'AI' END WHERE id = :i AND (gender_source IS NULL OR gender_source = 'AI')").param("g", g).param("i", customerId).update();
    }

    /** The customer chose it in their own profile: it applies in every shop they bought from. */
    public void setSelf(UUID clientId, String gender) {
        String g = normalize(gender);
        jdbc.sql("UPDATE commerce.client_accounts SET gender = :g WHERE id = :u").param("g", g).param("u", clientId).update();
        if (g != null) jdbc.sql("UPDATE commerce.customers SET gender = :g, gender_source = 'SELF' WHERE user_id = :u").param("g", g).param("u", clientId).update();
    }

    /** The shop owner sets or clears it. Clearing hands the customer back to the guess. */
    public void setByOwner(UUID tenantId, UUID customerId, String gender) {
        String g = normalize(gender);
        var r = jdbc.sql("SELECT name, gender_source FROM commerce.customers WHERE id = :i AND tenant_id = :t").param("i", customerId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> com.platform.shared.BusinessException.notFound("RESOURCE_NOT_FOUND", "Customer not found"));
        if (g == null) { jdbc.sql("UPDATE commerce.customers SET gender = NULL, gender_source = NULL WHERE id = :i").param("i", customerId).update(); refresh(customerId, (String) r.get("name"), null); return; }
        jdbc.sql("UPDATE commerce.customers SET gender = :g, gender_source = 'OWNER' WHERE id = :i AND tenant_id = :t AND coalesce(gender_source, 'AI') <> 'SELF'").param("g", g).param("i", customerId).param("t", tenantId).update();
    }

    private static String normalize(String gender) {
        if (gender == null || gender.isBlank()) return null;
        String g = gender.trim().toUpperCase();
        if (!g.equals("MALE") && !g.equals("FEMALE")) throw com.platform.shared.BusinessException.badRequest("VALIDATION_ERROR", "Gender must be MALE or FEMALE");
        return g;
    }
}
