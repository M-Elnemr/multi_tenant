package com.platform.core.tenant;

import com.platform.shared.Page;
import com.platform.shared.PatientPortalPolicy;
import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public doctor directory for the patient app: every active doctor of every live clinic, filterable by specialty, city, consultation fee, visit
 * type and "books online". Only what the clinics already publish on their own websites is returned (name, photo, specialties, experience, fee,
 * the clinic's public contact). Ratings are not offered: the platform has no patient reviews for doctors.
 */
@RestController
@RequestMapping("/api/v1/tenant/doctors")
public class DoctorDirectoryController {
    private static final String LIVE = "t.tenant_type = 'CLINIC' AND t.status IN ('TRIAL','ACTIVE','PAST_DUE') AND d.is_active"
            + " AND EXISTS (SELECT 1 FROM core.tenant_domains td WHERE td.tenant_id = t.id AND td.is_primary AND td.is_verified)";
    private static final java.util.Set<String> SORTS = java.util.Set.of("name", "fee_asc", "fee_desc", "experience");
    private final JdbcClient jdbc;
    private final PatientPortalPolicy policy;

    public DoctorDirectoryController(JdbcClient jdbc, PatientPortalPolicy policy) { this.jdbc = jdbc; this.policy = policy; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) String specialty, @RequestParam(required = false) String city,
                                    @RequestParam(required = false) Long minFee, @RequestParam(required = false) Long maxFee, @RequestParam(required = false) String visitType,
                                    @RequestParam(defaultValue = "false") boolean bookingOnly, @RequestParam(defaultValue = "name") String sort,
                                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        Page pg = new Page(Math.max(1, page), Math.min(Math.max(1, pageSize), 50));
        String like = blank(q) == null ? null : "%" + q.trim() + "%";
        String vt = "CONSULTATION".equals(visitType) || "FOLLOW_UP".equals(visitType) ? visitType : null;
        boolean onlineOnly = bookingOnly && policy.bookingEnabled();
        String where = LIVE + """
                 AND (CAST(:like AS varchar) IS NULL OR d.display_name ILIKE CAST(:like AS varchar) OR t.name ILIKE CAST(:like AS varchar) OR cp.clinic_name ILIKE CAST(:like AS varchar))
                 AND (CAST(:spec AS varchar) IS NULL OR EXISTS (SELECT 1 FROM medical.doctor_specialties ds JOIN medical.specialties s ON s.id = ds.specialty_id WHERE ds.doctor_id = d.id AND s.code = CAST(:spec AS varchar)))
                 AND (CAST(:city AS varchar) IS NULL OR EXISTS (SELECT 1 FROM medical.clinic_branches b WHERE b.tenant_id = t.id AND b.is_active AND b.city = CAST(:city AS varchar)))
                 AND (CAST(:minFee AS bigint) IS NULL OR d.default_appointment_fee_minor >= CAST(:minFee AS bigint))
                 AND (CAST(:maxFee AS bigint) IS NULL OR d.default_appointment_fee_minor <= CAST(:maxFee AS bigint))
                 AND (CAST(:vt AS varchar) IS NULL OR EXISTS (SELECT 1 FROM medical.appointment_services sv WHERE sv.tenant_id = t.id AND sv.is_active AND sv.visit_type = CAST(:vt AS varchar)))
                 AND (NOT :online OR coalesce(cp.booking_enabled, false))
                """;
        String order = switch (SORTS.contains(sort) ? sort : "name") {
            case "fee_asc" -> "d.default_appointment_fee_minor ASC NULLS LAST, d.display_name";
            case "fee_desc" -> "d.default_appointment_fee_minor DESC NULLS LAST, d.display_name";
            case "experience" -> "d.years_experience DESC NULLS LAST, d.display_name";
            default -> "d.display_name";
        };
        String from = " FROM medical.doctors d JOIN core.tenants t ON t.id = d.tenant_id LEFT JOIN medical.clinic_profiles cp ON cp.tenant_id = t.id LEFT JOIN core.branding br ON br.tenant_id = t.id WHERE ";
        var params = new LinkedHashMap<String, Object>();
        params.put("like", like); params.put("spec", blank(specialty)); params.put("city", blank(city)); params.put("minFee", minFee); params.put("maxFee", maxFee); params.put("vt", vt); params.put("online", onlineOnly);
        var countSql = jdbc.sql("SELECT count(*)" + from + where);
        for (var e : params.entrySet()) countSql = countSql.param(e.getKey(), e.getValue());
        long total = countSql.query(Long.class).single();
        var rowsSql = jdbc.sql("""
                SELECT d.id, d.display_name, d.bio, d.years_experience, d.qualifications, d.languages::text AS languages, d.default_appointment_fee_minor, d.currency, d.profile_image_file_id, d.other_specialty,
                       t.slug, coalesce(nullif(cp.clinic_name, ''), t.name) AS clinic_name, cp.phone, cp.whatsapp, cp.address_text, cp.maps_url, coalesce(cp.booking_enabled, false) AS booking_enabled,
                       br.primary_color, br.logo_file_id,
                       (SELECT td.host FROM core.tenant_domains td WHERE td.tenant_id = t.id AND td.is_primary AND td.is_verified) AS host,
                       coalesce((SELECT json_agg(json_build_object('code', s.code, 'nameAr', s.name_ar, 'nameEn', s.name_en))::text FROM medical.doctor_specialties ds JOIN medical.specialties s ON s.id = ds.specialty_id WHERE ds.doctor_id = d.id), '[]') AS specialties,
                       coalesce((SELECT json_agg(DISTINCT b.city)::text FROM medical.clinic_branches b WHERE b.tenant_id = t.id AND b.is_active AND coalesce(b.city, '') <> ''), '[]') AS cities,
                       coalesce((SELECT json_agg(DISTINCT sv.visit_type)::text FROM medical.appointment_services sv WHERE sv.tenant_id = t.id AND sv.is_active AND sv.visit_type IS NOT NULL), '[]') AS visit_types
                """ + from + where + " ORDER BY " + order + " LIMIT :lim OFFSET :off").param("lim", pg.pageSize()).param("off", pg.offset());
        for (var e : params.entrySet()) rowsSql = rowsSql.param(e.getKey(), e.getValue());
        boolean online = policy.bookingEnabled();
        List<Map<String, Object>> data = Rows.camel(rowsSql.query().listOfRows()).stream().map(r -> {
            String host = (String) r.get("host");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("name", r.get("displayName"));
            m.put("bio", r.get("bio"));
            m.put("yearsExperience", r.get("yearsExperience"));
            m.put("qualifications", r.get("qualifications"));
            m.put("languages", Rows.jsonList(r.get("languages")));
            m.put("feeMinor", r.get("defaultAppointmentFeeMinor"));
            m.put("currency", r.get("currency") == null ? "EGP" : r.get("currency"));
            m.put("photoUrl", r.get("profileImageFileId") == null ? null : "https://" + host + "/api/v1/files/" + r.get("profileImageFileId") + "/content?variant=medium");
            m.put("otherSpecialty", r.get("otherSpecialty"));
            m.put("specialties", Rows.jsonList(r.get("specialties")));
            Map<String, Object> clinic = new LinkedHashMap<>();
            clinic.put("slug", r.get("slug"));
            clinic.put("name", r.get("clinicName"));
            clinic.put("host", host);
            clinic.put("baseUrl", "https://" + host);
            clinic.put("logoUrl", r.get("logoFileId") == null ? null : "https://" + host + "/api/v1/files/" + r.get("logoFileId") + "/content?variant=thumb");
            clinic.put("primaryColor", r.get("primaryColor"));
            clinic.put("phone", r.get("phone"));
            clinic.put("whatsapp", r.get("whatsapp"));
            clinic.put("address", r.get("addressText"));
            clinic.put("mapsUrl", r.get("mapsUrl"));
            clinic.put("cities", Rows.jsonList(r.get("cities")));
            clinic.put("visitTypes", Rows.jsonList(r.get("visitTypes")));
            clinic.put("bookingEnabled", online && Boolean.TRUE.equals(r.get("bookingEnabled")));
            m.put("clinic", clinic);
            return m;
        }).toList();
        return pg.wrap(data, total);
    }

    /** Only specialties and cities that a listed doctor really has, and the real fee range, so filters never lead to an empty list. */
    @GetMapping("/filters")
    public Map<String, Object> filters() {
        String from = " FROM medical.doctors d JOIN core.tenants t ON t.id = d.tenant_id WHERE " + LIVE;
        var specs = jdbc.sql("SELECT s.code, s.name_ar, s.name_en, count(DISTINCT d.id) AS n FROM medical.doctor_specialties ds JOIN medical.specialties s ON s.id = ds.specialty_id JOIN medical.doctors d ON d.id = ds.doctor_id JOIN core.tenants t ON t.id = d.tenant_id WHERE "
                + LIVE + " GROUP BY s.code, s.name_ar, s.name_en, s.sort_order ORDER BY n DESC, s.sort_order")
                .query().listOfRows().stream().map(r -> Map.of("code", r.get("code"), "nameAr", r.get("name_ar"), "nameEn", r.get("name_en"), "n", r.get("n"))).toList();
        var cities = jdbc.sql("SELECT DISTINCT b.city FROM medical.clinic_branches b JOIN core.tenants t ON t.id = b.tenant_id JOIN medical.doctors d ON d.tenant_id = t.id WHERE " + LIVE
                + " AND b.is_active AND coalesce(b.city, '') <> '' ORDER BY b.city").query(String.class).list();
        var fee = jdbc.sql("SELECT min(d.default_appointment_fee_minor) AS lo, max(d.default_appointment_fee_minor) AS hi" + from).query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("specialties", specs);
        out.put("cities", cities);
        out.put("feeMinMinor", fee.get("lo"));
        out.put("feeMaxMinor", fee.get("hi"));
        out.put("onlineBooking", policy.bookingEnabled());
        return out;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
