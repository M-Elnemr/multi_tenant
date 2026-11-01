package com.platform.legal;

import com.platform.core.onboarding.ProvisionOptions;
import com.platform.core.onboarding.TenantProvisioner;
import com.platform.core.tenant.Tenant;
import com.platform.core.tenant.TenantType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A new lawyer gets a ready website (profile, practice areas, services, hours) so clients can book on day one. */
@Component
public class LawyerProvisioner implements TenantProvisioner {
    /** code, Arabic, English, icon: the practice areas offered at sign-up and shown on the website. */
    static final List<String[]> AREAS = List.of(
            new String[]{"criminal", "القضايا الجنائية", "Criminal law", "🛡️"},
            new String[]{"family", "الأحوال الشخصية والأسرة", "Family & personal status", "👪"},
            new String[]{"civil", "القضايا المدنية", "Civil litigation", "⚖️"},
            new String[]{"commercial", "القانون التجاري والشركات", "Commercial & corporate", "🏢"},
            new String[]{"labour", "قضايا العمل والعمال", "Labour & employment", "👷"},
            new String[]{"real_estate", "العقارات والإيجارات", "Real estate & leases", "🏠"},
            new String[]{"administrative", "القضاء الإداري ومجلس الدولة", "Administrative law", "🏛️"},
            new String[]{"economic", "الجرائم الاقتصادية", "Economic crimes", "📊"},
            new String[]{"inheritance", "المواريث والتركات", "Inheritance & estates", "📜"},
            new String[]{"contracts", "صياغة ومراجعة العقود", "Contract drafting & review", "✍️"},
            new String[]{"intellectual_property", "الملكية الفكرية والعلامات التجارية", "Intellectual property", "💡"},
            new String[]{"tax", "القضايا الضريبية", "Tax disputes", "🧾"},
            new String[]{"arbitration", "التحكيم وفض المنازعات", "Arbitration & mediation", "🤝"},
            new String[]{"cassation", "الطعن بالنقض", "Cassation appeals", "🔨"},
            new String[]{"other", "أخرى", "Other", "📁"});

    private final JdbcClient jdbc;

    public LawyerProvisioner(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public TenantType supports() { return TenantType.LAWYER; }

    @Override
    public List<Map<String, Object>> categories() {
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (String[] a : AREAS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", a[0]); m.put("nameAr", a[1]); m.put("nameEn", a[2]); m.put("popular", i++ < 6);
            out.add(m);
        }
        return out;
    }

    @Override
    public void provision(Tenant t, UUID ownerUserId) { provision(t, ownerUserId, ProvisionOptions.none()); }

    @Override
    public void provision(Tenant t, UUID ownerUserId, ProvisionOptions options) {
        ProvisionOptions o = options.validated("your practice areas");
        List<Map<String, Object>> areas = new ArrayList<>();
        for (String code : o.categories().isEmpty() ? List.of("civil", "criminal", "family", "commercial") : o.categories()) {
            String[] a = AREAS.stream().filter(x -> x[0].equals(code)).findFirst().orElse(null);
            if (a == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", a[0]); m.put("title", "other".equals(a[0]) && o.otherCategory() != null ? o.otherCategory() : a[1]); m.put("description", ""); m.put("icon", a[3]);
            areas.add(m);
        }
        String phone = jdbc.sql("SELECT coalesce(phone, '') FROM core.users WHERE id = :u").param("u", ownerUserId).query(String.class).optional().orElse("");
        jdbc.sql("""
                INSERT INTO legal.profiles (tenant_id, display_name, title, headline, practice_areas, phone, whatsapp, achievements, faq)
                VALUES (:t, :n, 'المحامي', 'استشارات قانونية وتمثيل أمام جميع المحاكم', CAST(:a AS jsonb), :p, :p, CAST(:ach AS jsonb), CAST(:faq AS jsonb))
                """).param("t", t.getId()).param("n", t.getName()).param("a", Json.write(areas)).param("p", phone)
                .param("ach", Json.write(List.of(Map.of("value", "10+", "label", "سنوات خبرة"), Map.of("value", "300+", "label", "قضية"), Map.of("value", "95%", "label", "رضا العملاء"))))
                .param("faq", Json.write(List.of(
                        Map.of("q", "كيف أحجز استشارة؟", "a", "اختر الخدمة والموعد المناسب من صفحة الحجز، وسيتم تأكيد الموعد خلال وقت قصير."),
                        Map.of("q", "هل الاستشارة سرية؟", "a", "نعم، كل ما تشاركه معنا يخضع لسرية المهنة بالكامل."),
                        Map.of("q", "ماذا أحضر معي في الموعد؟", "a", "صورة بطاقة الرقم القومي وأي مستندات أو أحكام متعلقة بالموضوع."))))
                .update();
        jdbc.sql("""
                INSERT INTO legal.services (tenant_id, name, description, duration_minutes, price_minor, mode, sort_order) VALUES
                 (:t, 'استشارة قانونية', 'جلسة 30 دقيقة لفهم موقفك القانوني والخطوات المقترحة.', 30, NULL, 'OFFICE', 0),
                 (:t, 'استشارة هاتفية', 'استشارة سريعة عبر الهاتف في الوقت المحدد.', 30, NULL, 'PHONE', 1),
                 (:t, 'مراجعة عقد', 'مراجعة العقد وبيان المخاطر وصياغة التعديلات.', 30, NULL, 'OFFICE', 2)
                """).param("t", t.getId()).update();
        // Saturday-Thursday 11:00-17:00 (ISO: 6,7,1,2,3,4); the lawyer edits this in the dashboard.
        for (int d : new int[]{6, 7, 1, 2, 3, 4})
            jdbc.sql("INSERT INTO legal.working_hours (tenant_id, weekday, start_time, end_time) VALUES (:t, :d, '11:00', '17:00')").param("t", t.getId()).param("d", d).update();
    }
}
