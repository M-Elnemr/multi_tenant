package com.platform.booking;

import com.platform.commerce.StoreCustomerService;
import com.platform.notifications.NotificationService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.security.SecureRandom;
import java.sql.Time;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A shop that sells services by appointment (a haircut, make-up, a massage): its services, opening hours, days off, and the appointments customers ask for (in the chat or from the
 * dashboard). A request is PENDING until the shop confirms it (or CONFIRMED at once when the shop chose automatic confirmation). Times are in the shop's timezone; how many customers can
 * be served at the same time (chairs, rooms, staff) is the shop's {@code stations}, and no time is offered or accepted once all of them are busy.
 */
@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final String LIVE = "('PENDING','CONFIRMED')";

    public record ServiceReq(String name, String description, Integer durationMinutes, Integer bufferMinutes, Long priceMinor, Boolean active, Integer sortOrder,
                             UUID categoryId, String shortDescription, Long priceBeforeMinor, Integer maxConcurrent, String locationMode, String serviceArea, String instructions, String cancellationPolicy,
                             UUID imageFileId, List<UUID> gallery, List<Map<String, Object>> requiredFields) {
        public ServiceReq(String name, String description, Integer durationMinutes, Integer bufferMinutes, Long priceMinor, Boolean active, Integer sortOrder) {
            this(name, description, durationMinutes, bufferMinutes, priceMinor, active, sortOrder, null, null, null, null, null, null, null, null, null, null, null);
        }
    }
    public record CategoryReq(String name, Integer sortOrder) {}
    public record AddonReq(String name, Long priceMinor, Integer durationMinutes, Boolean active, Integer sortOrder) {}
    public record SpecialDayReq(String date, List<Range> ranges) {}
    public record Range(int weekday, String opens, String closes) {}
    public record SettingsReq(Integer stations, Integer slotStepMinutes, Integer minNoticeMinutes, Integer maxDaysAhead, Boolean autoConfirm, Integer pendingExpiresMinutes, Integer cancelDeadlineHours) {
        public SettingsReq(Integer stations, Integer slotStepMinutes, Integer minNoticeMinutes, Integer maxDaysAhead, Boolean autoConfirm) { this(stations, slotStepMinutes, minNoticeMinutes, maxDaysAhead, autoConfirm, null, null); }
    }
    public record BookReq(UUID serviceId, String date, String time, String name, String phone, String note, String source, UUID conversationId,
                          List<UUID> addonIds, Map<String, String> answers, String locationMode, String address) {
        public BookReq(UUID serviceId, String date, String time, String name, String phone, String note, String source, UUID conversationId) { this(serviceId, date, time, name, phone, note, source, conversationId, null, null, null, null); }
    }

    private final JdbcClient jdbc;
    private final StoreCustomerService customers;
    private final NotificationService notifications;
    private final com.platform.chat.ChatDelivery delivery;

    public BookingService(JdbcClient jdbc, StoreCustomerService customers, NotificationService notifications, @Lazy com.platform.chat.ChatDelivery delivery) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.notifications = notifications;
        this.delivery = delivery;
    }

    // ---- services ------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> services(UUID tenantId, boolean activeOnly) {
        var rows = jdbc.sql(SERVICE_COLS + " FROM commerce.services s LEFT JOIN commerce.service_categories c ON c.id = s.category_id WHERE s.tenant_id = :t" + (activeOnly ? " AND s.is_active" : "") + " ORDER BY s.sort_order, s.name")
                .param("t", tenantId).query().listOfRows();
        var addons = jdbc.sql("SELECT id, service_id, name, price_minor, duration_minutes, is_active, sort_order FROM commerce.service_addons WHERE tenant_id = :t" + (activeOnly ? " AND is_active" : "") + " ORDER BY sort_order, name").param("t", tenantId).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) out.add(serviceView(r, addons.stream().filter(a -> a.get("service_id").equals(r.get("id"))).toList()));
        return out;
    }

    private static final String SERVICE_COLS = "SELECT s.id, s.name, s.description, s.duration_minutes, s.buffer_minutes, s.price_minor, s.currency, s.is_active, s.sort_order, s.category_id, c.name AS category_name, s.short_description, "
            + "s.price_before_minor, s.max_concurrent, s.location_mode, s.service_area, s.instructions, s.cancellation_policy, s.image_file_id, array_to_string(s.gallery, ',') AS gallery_csv, s.required_fields::text AS required_fields_json";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private Map<String, Object> serviceView(Map<String, Object> r, List<Map<String, Object>> addons) {
        Map<String, Object> m = Rows.camel(r);
        String g = (String) m.remove("galleryCsv");
        m.put("gallery", g == null || g.isBlank() ? List.of() : List.of(g.split(",")));
        try { m.put("requiredFields", JSON.readValue((String) m.remove("requiredFieldsJson"), List.class)); } catch (Exception e) { m.put("requiredFields", List.of()); }
        m.put("addons", Rows.camel(addons).stream().peek(a -> a.remove("serviceId")).toList());
        return m;
    }

    private static String clean(String s, int max, String field) {
        String v = s == null ? "" : s.trim();
        if (v.length() > max) throw BusinessException.badRequest("VALIDATION_ERROR", "Too long").field(field);
        return v;
    }

    @Transactional
    public Map<String, Object> createService(UUID tenantId, ServiceReq r) {
        String name = clean(r.name(), 160, "name");
        if (name.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Service name is required").field("name");
        if (r.durationMinutes() == null || r.durationMinutes() < 5 || r.durationMinutes() > 720) throw BusinessException.badRequest("VALIDATION_ERROR", "Duration must be 5 to 720 minutes").field("durationMinutes");
        long price = r.priceMinor() == null ? 0 : r.priceMinor();
        if (price < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Price cannot be negative").field("priceMinor");
        int buffer = r.bufferMinutes() == null ? 0 : r.bufferMinutes();
        if (buffer < 0 || buffer > 240) throw BusinessException.badRequest("VALIDATION_ERROR", "Break after the service must be 0 to 240 minutes").field("bufferMinutes");
        String currency = jdbc.sql("SELECT default_currency FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single();
        UUID id = jdbc.sql("INSERT INTO commerce.services (tenant_id, name, description, duration_minutes, buffer_minutes, price_minor, currency, sort_order) VALUES (:t, :n, :d, :du, :b, :p, :c, :so) RETURNING id")
                .param("t", tenantId).param("n", name).param("d", clean(r.description(), 2000, "description")).param("du", r.durationMinutes()).param("b", buffer).param("p", price).param("c", currency).param("so", r.sortOrder() == null ? 0 : r.sortOrder())
                .query(UUID.class).single();
        applyServiceExtras(tenantId, id, r);
        return service(tenantId, id);
    }

    @Transactional
    public Map<String, Object> updateService(UUID tenantId, UUID id, ServiceReq r) {
        service(tenantId, id);
        if (r.durationMinutes() != null && (r.durationMinutes() < 5 || r.durationMinutes() > 720)) throw BusinessException.badRequest("VALIDATION_ERROR", "Duration must be 5 to 720 minutes").field("durationMinutes");
        if (r.priceMinor() != null && r.priceMinor() < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Price cannot be negative").field("priceMinor");
        if (r.bufferMinutes() != null && (r.bufferMinutes() < 0 || r.bufferMinutes() > 240)) throw BusinessException.badRequest("VALIDATION_ERROR", "Break after the service must be 0 to 240 minutes").field("bufferMinutes");
        if (r.name() != null && r.name().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Service name is required").field("name");
        jdbc.sql("""
                UPDATE commerce.services SET name = coalesce(:n, name), description = coalesce(:d, description), duration_minutes = coalesce(:du, duration_minutes), buffer_minutes = coalesce(:b, buffer_minutes),
                  price_minor = coalesce(:p, price_minor), is_active = coalesce(:a, is_active), sort_order = coalesce(:so, sort_order), updated_at = now() WHERE id = :i AND tenant_id = :t
                """).param("n", r.name() == null ? null : clean(r.name(), 160, "name")).param("d", r.description() == null ? null : clean(r.description(), 2000, "description")).param("du", r.durationMinutes()).param("b", r.bufferMinutes())
                .param("p", r.priceMinor()).param("a", r.active()).param("so", r.sortOrder()).param("i", id).param("t", tenantId).update();
        applyServiceExtras(tenantId, id, r);
        return service(tenantId, id);
    }

    private static final java.util.Set<String> LOCATIONS = java.util.Set.of("SHOP", "HOME", "BOTH");
    private static final java.util.regex.Pattern FIELD_KEY = java.util.regex.Pattern.compile("[a-z][a-zA-Z0-9]{0,30}");

    /** The v2 fields of a service. Only what the request carries changes: a null leaves the column as it is; an empty string clears a text. */
    private void applyServiceExtras(UUID tenantId, UUID id, ServiceReq r) {
        if (r.locationMode() != null && !LOCATIONS.contains(r.locationMode())) throw BusinessException.badRequest("VALIDATION_ERROR", "Location must be SHOP, HOME or BOTH").field("locationMode");
        if (r.maxConcurrent() != null && (r.maxConcurrent() < 1 || r.maxConcurrent() > 50)) throw BusinessException.badRequest("VALIDATION_ERROR", "Must be between 1 and 50").field("maxConcurrent");
        if (r.priceBeforeMinor() != null && r.priceBeforeMinor() < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Price cannot be negative").field("priceBeforeMinor");
        if (r.categoryId() != null && jdbc.sql("SELECT count(*) FROM commerce.service_categories WHERE id = :c AND tenant_id = :t").param("c", r.categoryId()).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown category").field("categoryId");
        String fields = null;
        if (r.requiredFields() != null) {
            if (r.requiredFields().size() > 12) throw BusinessException.badRequest("VALIDATION_ERROR", "Up to 12 questions").field("requiredFields");
            List<Map<String, Object>> clean = new ArrayList<>();
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (var f : r.requiredFields()) {
                String key = String.valueOf(f.getOrDefault("key", "")), label = clean(String.valueOf(f.getOrDefault("label", "")), 120, "requiredFields");
                if (!FIELD_KEY.matcher(key).matches() || label.isEmpty() || !keys.add(key)) throw BusinessException.badRequest("VALIDATION_ERROR", "Each question needs a unique key (letters and digits) and a label").field("requiredFields");
                clean.add(Map.of("key", key, "label", label, "required", !Boolean.FALSE.equals(f.get("required"))));
            }
            try { fields = JSON.writeValueAsString(clean); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        String gallery = r.gallery() == null ? null : r.gallery().stream().limit(12).map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
        jdbc.sql("""
                UPDATE commerce.services SET category_id = coalesce(:cat, category_id), short_description = coalesce(:sd, short_description), price_before_minor = coalesce(:pb, price_before_minor),
                  max_concurrent = coalesce(:mc, max_concurrent), location_mode = coalesce(:lm, location_mode), service_area = coalesce(:sa, service_area), instructions = coalesce(:ins, instructions),
                  cancellation_policy = coalesce(:cp, cancellation_policy), image_file_id = coalesce(:img, image_file_id),
                  gallery = coalesce(CAST(string_to_array(:gal, ',') AS uuid[]), gallery), required_fields = coalesce(CAST(:rf AS jsonb), required_fields), updated_at = now()
                WHERE id = :i AND tenant_id = :t
                """).param("cat", r.categoryId()).param("sd", r.shortDescription() == null ? null : clean(r.shortDescription(), 240, "shortDescription")).param("pb", r.priceBeforeMinor()).param("mc", r.maxConcurrent())
                .param("lm", r.locationMode()).param("sa", r.serviceArea() == null ? null : clean(r.serviceArea(), 1000, "serviceArea")).param("ins", r.instructions() == null ? null : clean(r.instructions(), 2000, "instructions"))
                .param("cp", r.cancellationPolicy() == null ? null : clean(r.cancellationPolicy(), 2000, "cancellationPolicy")).param("img", r.imageFileId()).param("gal", gallery == null ? null : gallery.isEmpty() ? "" : gallery)
                .param("rf", fields).param("i", id).param("t", tenantId).update();
    }

    // ---- categories and add-ons --------------------------------------------------------------------------------------

    public List<Map<String, Object>> categories(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, name, sort_order FROM commerce.service_categories WHERE tenant_id = :t ORDER BY sort_order, name").param("t", tenantId).query().listOfRows());
    }

    @Transactional
    public Map<String, Object> saveCategory(UUID tenantId, UUID id, CategoryReq r) {
        String name = clean(r.name(), 120, "name");
        if (name.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Category name is required").field("name");
        try {
            if (id == null) id = jdbc.sql("INSERT INTO commerce.service_categories (tenant_id, name, sort_order) VALUES (:t, :n, coalesce(:so, 0)) RETURNING id").param("t", tenantId).param("n", name).param("so", r.sortOrder()).query(UUID.class).single();
            else if (jdbc.sql("UPDATE commerce.service_categories SET name = :n, sort_order = coalesce(:so, sort_order) WHERE id = :i AND tenant_id = :t").param("n", name).param("so", r.sortOrder()).param("i", id).param("t", tenantId).update() == 0)
                throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Category not found");
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BusinessException.badRequest("VALIDATION_ERROR", "A category with this name exists").field("name");
        }
        UUID cid = id;
        return categories(tenantId).stream().filter(c -> cid.equals(c.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteCategory(UUID tenantId, UUID id) {
        jdbc.sql("DELETE FROM commerce.service_categories WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();   // its services stay, without a category
    }

    @Transactional
    public Map<String, Object> saveAddon(UUID tenantId, UUID serviceId, UUID id, AddonReq r) {
        service(tenantId, serviceId);
        String name = r.name() == null ? null : clean(r.name(), 120, "name");
        if (id == null && (name == null || name.isEmpty())) throw BusinessException.badRequest("VALIDATION_ERROR", "Add-on name is required").field("name");
        if (r.priceMinor() != null && r.priceMinor() < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Price cannot be negative").field("priceMinor");
        check(r.durationMinutes(), 0, 480, "durationMinutes");
        if (id == null) id = jdbc.sql("INSERT INTO commerce.service_addons (tenant_id, service_id, name, price_minor, duration_minutes, sort_order) VALUES (:t, :s, :n, coalesce(:p, 0), coalesce(:d, 0), coalesce(:so, 0)) RETURNING id")
                .param("t", tenantId).param("s", serviceId).param("n", name).param("p", r.priceMinor()).param("d", r.durationMinutes()).param("so", r.sortOrder()).query(UUID.class).single();
        else if (jdbc.sql("UPDATE commerce.service_addons SET name = coalesce(:n, name), price_minor = coalesce(:p, price_minor), duration_minutes = coalesce(:d, duration_minutes), is_active = coalesce(:a, is_active), sort_order = coalesce(:so, sort_order) WHERE id = :i AND service_id = :s AND tenant_id = :t")
                .param("n", name).param("p", r.priceMinor()).param("d", r.durationMinutes()).param("a", r.active()).param("so", r.sortOrder()).param("i", id).param("s", serviceId).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Add-on not found");
        UUID aid = id;
        return Rows.camel(jdbc.sql("SELECT id, name, price_minor, duration_minutes, is_active, sort_order FROM commerce.service_addons WHERE id = :i").param("i", aid).query().listOfRows().get(0));
    }

    @Transactional
    public void deleteAddon(UUID tenantId, UUID serviceId, UUID id) {
        jdbc.sql("UPDATE commerce.service_addons SET is_active = FALSE WHERE id = :i AND service_id = :s AND tenant_id = :t").param("i", id).param("s", serviceId).param("t", tenantId).update();   // past bookings keep their copy
    }

    // ---- special days (own hours on one date) and days off over a range ------------------------------------------------

    public List<Map<String, Object>> specialDays(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT day::text AS date, to_char(opens_at, 'HH24:MI') AS opens, to_char(closes_at, 'HH24:MI') AS closes FROM commerce.service_special_days WHERE tenant_id = :t AND day >= current_date - 1 ORDER BY day, opens_at").param("t", tenantId).query().listOfRows());
    }

    @Transactional
    public List<Map<String, Object>> saveSpecialDay(UUID tenantId, String date, List<Range> ranges) {
        LocalDate d = parseDate(date);
        List<Object[]> clean = new ArrayList<>();
        for (Range r : ranges == null ? List.<Range>of() : ranges) {
            LocalTime o = time(r.opens(), "opens"), c = time(r.closes(), "closes");
            if (!c.isAfter(o)) throw BusinessException.badRequest("VALIDATION_ERROR", "Closing time must be after opening time").field("closes");
            clean.add(new Object[] {o, c});
        }
        clean.sort(java.util.Comparator.comparing(a -> (LocalTime) a[0]));
        for (int i = 1; i < clean.size(); i++) if (((LocalTime) clean.get(i)[0]).isBefore((LocalTime) clean.get(i - 1)[1])) throw BusinessException.badRequest("VALIDATION_ERROR", "Hours of one day may not overlap").field("ranges");
        jdbc.sql("DELETE FROM commerce.service_special_days WHERE tenant_id = :t AND day = :d").param("t", tenantId).param("d", d).update();
        for (Object[] r : clean) jdbc.sql("INSERT INTO commerce.service_special_days (tenant_id, day, opens_at, closes_at) VALUES (:t, :d, :o, :c)").param("t", tenantId).param("d", d).param("o", java.sql.Time.valueOf((LocalTime) r[0])).param("c", java.sql.Time.valueOf((LocalTime) r[1])).update();
        return specialDays(tenantId);
    }

    /** A holiday or a vacation: every date from {@code from} to {@code to} (up to 120 days) is closed. */
    @Transactional
    public List<Map<String, Object>> addClosureRange(UUID tenantId, String from, String to, String reason) {
        LocalDate a = parseDate(from), b = to == null || to.isBlank() ? a : parseDate(to);
        if (b.isBefore(a) || a.plusDays(120).isBefore(b)) throw BusinessException.badRequest("VALIDATION_ERROR", "The closure can be up to 120 days").field("to");
        for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) addClosure(tenantId, d.toString(), reason);
        return closures(tenantId);
    }

    /** A service that was ever booked is switched off (its history stays); one never booked is deleted. */
    @Transactional
    public void deleteService(UUID tenantId, UUID id) {
        service(tenantId, id);
        boolean used = jdbc.sql("SELECT EXISTS (SELECT 1 FROM commerce.bookings WHERE service_id = :i)").param("i", id).query(Boolean.class).single();
        if (used) jdbc.sql("UPDATE commerce.services SET is_active = FALSE, updated_at = now() WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
        else jdbc.sql("DELETE FROM commerce.services WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
    }

    public Map<String, Object> service(UUID tenantId, UUID id) {
        return serviceOf(tenantId, id);
    }

    private Map<String, Object> serviceOf(UUID tenantId, UUID id) {
        var row = jdbc.sql(SERVICE_COLS + " FROM commerce.services s LEFT JOIN commerce.service_categories c ON c.id = s.category_id WHERE s.id = :i AND s.tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Service not found"));
        return serviceView(row, jdbc.sql("SELECT id, service_id, name, price_minor, duration_minutes, is_active, sort_order FROM commerce.service_addons WHERE service_id = :i AND is_active ORDER BY sort_order, name").param("i", id).query().listOfRows());
    }

    // ---- hours, days off, rules -----------------------------------------------------------------------------------

    public List<Map<String, Object>> hours(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT weekday, to_char(opens_at, 'HH24:MI') AS opens, to_char(closes_at, 'HH24:MI') AS closes FROM commerce.service_hours WHERE tenant_id = :t ORDER BY weekday, opens_at").param("t", tenantId).query().listOfRows());
    }

    private static LocalTime time(String s, String field) {
        try { return LocalTime.parse(s == null ? "" : PhoneNormalizer.latinDigits(s).trim()); } catch (RuntimeException e) { throw BusinessException.badRequest("VALIDATION_ERROR", "Use a time like 10:30").field(field); }
    }

    /** Replaces the weekly opening hours. Two ranges of one weekday make a break; they may not overlap. */
    @Transactional
    public List<Map<String, Object>> saveHours(UUID tenantId, List<Range> ranges) {
        List<Object[]> clean = new ArrayList<>();
        for (Range r : ranges == null ? List.<Range>of() : ranges) {
            if (r.weekday() < 1 || r.weekday() > 7) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown weekday").field("weekday");
            LocalTime o = time(r.opens(), "opens"), c = time(r.closes(), "closes");
            if (!c.isAfter(o)) throw BusinessException.badRequest("VALIDATION_ERROR", "Closing time must be after opening time").field("closes");
            for (Object[] x : clean) if ((int) x[0] == r.weekday() && o.isBefore((LocalTime) x[2]) && c.isAfter((LocalTime) x[1])) throw BusinessException.badRequest("VALIDATION_ERROR", "Two hours of the same day overlap").field("opens");
            clean.add(new Object[] {r.weekday(), o, c});
        }
        jdbc.sql("DELETE FROM commerce.service_hours WHERE tenant_id = :t").param("t", tenantId).update();
        for (Object[] x : clean)
            jdbc.sql("INSERT INTO commerce.service_hours (tenant_id, weekday, opens_at, closes_at) VALUES (:t, :w, :o, :c)").param("t", tenantId).param("w", x[0]).param("o", Time.valueOf((LocalTime) x[1])).param("c", Time.valueOf((LocalTime) x[2])).update();
        return hours(tenantId);
    }

    public List<Map<String, Object>> closures(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT closed_on::text AS date, reason FROM commerce.service_closures WHERE tenant_id = :t AND closed_on >= current_date - 1 ORDER BY closed_on").param("t", tenantId).query().listOfRows());
    }

    @Transactional
    public List<Map<String, Object>> addClosure(UUID tenantId, String date, String reason) {
        LocalDate d = parseDate(date);
        jdbc.sql("INSERT INTO commerce.service_closures (tenant_id, closed_on, reason) VALUES (:t, :d, :r) ON CONFLICT (tenant_id, closed_on) DO UPDATE SET reason = :r").param("t", tenantId).param("d", d).param("r", clean(reason, 160, "reason")).update();
        return closures(tenantId);
    }

    @Transactional
    public List<Map<String, Object>> removeClosure(UUID tenantId, String date) {
        jdbc.sql("DELETE FROM commerce.service_closures WHERE tenant_id = :t AND closed_on = :d").param("t", tenantId).param("d", parseDate(date)).update();
        return closures(tenantId);
    }

    public Map<String, Object> settings(UUID tenantId) {
        var row = jdbc.sql("SELECT stations, slot_step_minutes, min_notice_minutes, max_days_ahead, auto_confirm, pending_expires_minutes, cancel_deadline_hours FROM commerce.booking_settings WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stations", row.map(r -> r.get("stations")).orElse(1));
        m.put("slotStepMinutes", row.map(r -> r.get("slot_step_minutes")).orElse(30));
        m.put("minNoticeMinutes", row.map(r -> r.get("min_notice_minutes")).orElse(120));
        m.put("maxDaysAhead", row.map(r -> r.get("max_days_ahead")).orElse(30));
        m.put("autoConfirm", row.map(r -> r.get("auto_confirm")).orElse(false));
        m.put("pendingExpiresMinutes", row.map(r -> r.get("pending_expires_minutes")).orElse(null));
        m.put("cancelDeadlineHours", row.map(r -> r.get("cancel_deadline_hours")).orElse(0));
        m.put("timezone", zone(tenantId).getId());
        return m;
    }

    @Transactional
    public Map<String, Object> saveSettings(UUID tenantId, SettingsReq r) {
        check(r.stations(), 1, 50, "stations");
        check(r.slotStepMinutes(), 5, 240, "slotStepMinutes");
        check(r.minNoticeMinutes(), 0, 10080, "minNoticeMinutes");
        check(r.maxDaysAhead(), 1, 365, "maxDaysAhead");
        check(r.pendingExpiresMinutes(), 5, 10080, "pendingExpiresMinutes");
        check(r.cancelDeadlineHours(), 0, 720, "cancelDeadlineHours");
        jdbc.sql("""
                INSERT INTO commerce.booking_settings (tenant_id, stations, slot_step_minutes, min_notice_minutes, max_days_ahead, auto_confirm, pending_expires_minutes, cancel_deadline_hours)
                VALUES (:t, coalesce(:s, 1), coalesce(:st, 30), coalesce(:mn, 120), coalesce(:md, 30), coalesce(:ac, FALSE), :pe, coalesce(:cd, 0))
                ON CONFLICT (tenant_id) DO UPDATE SET stations = coalesce(:s, commerce.booking_settings.stations), slot_step_minutes = coalesce(:st, commerce.booking_settings.slot_step_minutes),
                  min_notice_minutes = coalesce(:mn, commerce.booking_settings.min_notice_minutes), max_days_ahead = coalesce(:md, commerce.booking_settings.max_days_ahead),
                  auto_confirm = coalesce(:ac, commerce.booking_settings.auto_confirm), pending_expires_minutes = coalesce(:pe, commerce.booking_settings.pending_expires_minutes),
                  cancel_deadline_hours = coalesce(:cd, commerce.booking_settings.cancel_deadline_hours), updated_at = now()
                """).param("t", tenantId).param("s", r.stations()).param("st", r.slotStepMinutes()).param("mn", r.minNoticeMinutes()).param("md", r.maxDaysAhead()).param("ac", r.autoConfirm()).param("pe", r.pendingExpiresMinutes()).param("cd", r.cancelDeadlineHours()).update();
        return settings(tenantId);
    }

    private static void check(Integer v, int lo, int hi, String field) {
        if (v != null && (v < lo || v > hi)) throw BusinessException.badRequest("VALIDATION_ERROR", "Must be between " + lo + " and " + hi).field(field);
    }

    public ZoneId zone(UUID tenantId) { return ZoneId.of(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single()); }

    private static LocalDate parseDate(String s) {
        try { return LocalDate.parse(PhoneNormalizer.latinDigits(s == null ? "" : s).trim()); } catch (RuntimeException e) { throw BusinessException.badRequest("VALIDATION_ERROR", "Use a date like 2026-12-31").field("date"); }
    }

    // ---- free times -----------------------------------------------------------------------------------------------

    /** The start times (HH:mm, shop time) a customer can still book for this service on this day. {@code enforceRules}: minimum notice and how far ahead (customers); the shop itself may book closer in. */
    public List<String> slots(UUID tenantId, UUID serviceId, LocalDate date, boolean enforceRules) { return slots(tenantId, serviceId, date, enforceRules, List.of()); }

    public List<String> slots(UUID tenantId, UUID serviceId, LocalDate date, boolean enforceRules, List<UUID> addonIds) {
        var svc = service(tenantId, serviceId);
        return slotTimes(tenantId, svc, date, enforceRules, null, extras(tenantId, svc, addonIds).minutes()).stream().map(t -> t.format(DateTimeFormatter.ofPattern("HH:mm"))).toList();
    }

    private record Extras(int minutes, long priceMinor, List<Map<String, Object>> items) {}

    /** The add-ons the customer chose, each checked to belong to this service and be on offer; their minutes and price. */
    private Extras extras(UUID tenantId, Map<String, Object> svc, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return new Extras(0, 0, List.of());
        int minutes = 0;
        long price = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (UUID id : new java.util.LinkedHashSet<>(ids)) {
            var a = jdbc.sql("SELECT id, name, price_minor, duration_minutes FROM commerce.service_addons WHERE id = :i AND tenant_id = :t AND service_id = :s AND is_active").param("i", id).param("t", tenantId).param("s", svc.get("id")).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> BusinessException.badRequest("ADDON_UNAVAILABLE", "This extra is not available for this service").field("addonIds"));
            minutes += ((Number) a.get("duration_minutes")).intValue();
            price += ((Number) a.get("price_minor")).longValue();
            items.add(new LinkedHashMap<>(Map.of("id", a.get("id").toString(), "name", a.get("name"), "priceMinor", a.get("price_minor"), "durationMinutes", a.get("duration_minutes"))));
        }
        return new Extras(minutes, price, items);
    }

    /** What this service needs from the customer (its questions): every required one must be answered. */
    private Map<String, String> answers(Map<String, Object> svc, Map<String, String> given, String locationMode, String address) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var f : (List<Map<String, Object>>) svc.get("requiredFields")) {
            String key = String.valueOf(f.get("key")), v = given == null || given.get(key) == null ? "" : given.get(key).trim();
            if (v.length() > 500) throw BusinessException.badRequest("VALIDATION_ERROR", "Too long").field(key);
            if (v.isEmpty() && !Boolean.FALSE.equals(f.get("required"))) throw BusinessException.badRequest("VALIDATION_ERROR", String.valueOf(f.get("label")) + " is required").field(key);
            if (!v.isEmpty()) out.put(key, v);
        }
        return out;
    }

    private List<LocalTime> slotTimes(UUID tenantId, Map<String, Object> svc, LocalDate date, boolean enforceRules, UUID ignoreBooking) { return slotTimes(tenantId, svc, date, enforceRules, ignoreBooking, 0); }

    /** {@code extraMinutes}: the add-ons the customer chose make the appointment longer, so a time that fits the service alone may not fit with them. */
    private List<LocalTime> slotTimes(UUID tenantId, Map<String, Object> svc, LocalDate date, boolean enforceRules, UUID ignoreBooking, int extraMinutes) {
        if (!Boolean.TRUE.equals(svc.get("isActive"))) return List.of();
        ZoneId tz = zone(tenantId);
        var st = settings(tenantId);
        int step = ((Number) st.get("slotStepMinutes")).intValue(), stations = ((Number) st.get("stations")).intValue();
        int dur = ((Number) svc.get("durationMinutes")).intValue() + extraMinutes, buf = ((Number) svc.get("bufferMinutes")).intValue();
        Integer serviceCap = svc.get("maxConcurrent") == null ? null : ((Number) svc.get("maxConcurrent")).intValue();
        Object pendingExpiry = st.get("pendingExpiresMinutes");
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM commerce.service_closures WHERE tenant_id = :t AND closed_on = :d)").param("t", tenantId).param("d", date).query(Boolean.class).single()) return List.of();
        Instant now = Instant.now();
        if (enforceRules) {
            LocalDate today = LocalDate.now(tz);
            if (date.isBefore(today) || date.isAfter(today.plusDays(((Number) st.get("maxDaysAhead")).longValue()))) return List.of();
        } else if (date.isBefore(LocalDate.now(tz))) return List.of();
        Instant earliest = enforceRules ? now.plus(Duration.ofMinutes(((Number) st.get("minNoticeMinutes")).longValue())) : now;
        var ranges = jdbc.sql("SELECT opens_at, closes_at FROM commerce.service_special_days WHERE tenant_id = :t AND day = :d ORDER BY opens_at").param("t", tenantId).param("d", date).query().listOfRows();   // a date with its own hours replaces the weekday's
        if (ranges.isEmpty()) ranges = jdbc.sql("SELECT opens_at, closes_at FROM commerce.service_hours WHERE tenant_id = :t AND weekday = :w ORDER BY opens_at").param("t", tenantId).param("w", date.getDayOfWeek().getValue()).query().listOfRows();
        if (ranges.isEmpty()) return List.of();
        Instant dayStart = date.atStartOfDay(tz).toInstant(), dayEnd = date.plusDays(1).atStartOfDay(tz).toInstant();
        var busy = jdbc.sql("SELECT starts_at, blocks_until, service_id FROM commerce.bookings WHERE tenant_id = :t AND (status = 'CONFIRMED' OR (status = 'PENDING' AND (CAST(:pe AS int) IS NULL OR created_at > now() - make_interval(mins => CAST(:pe AS int))))) AND starts_at < :e AND blocks_until > :s AND (CAST(:ig AS uuid) IS NULL OR id <> CAST(:ig AS uuid))")
                .param("pe", pendingExpiry).param("t", tenantId).param("s", java.sql.Timestamp.from(dayStart.minus(Duration.ofHours(12)))).param("e", java.sql.Timestamp.from(dayEnd)).param("ig", ignoreBooking).query().listOfRows();
        List<LocalTime> out = new ArrayList<>();
        for (var r : ranges) {
            LocalTime opens = ((Time) r.get("opens_at")).toLocalTime(), closes = ((Time) r.get("closes_at")).toLocalTime();
            for (LocalTime t = opens; !t.plusMinutes(dur).isAfter(closes) && !t.plusMinutes(dur).isBefore(t); t = t.plusMinutes(step)) {
                Instant start = ZonedDateTime.of(date, t, tz).toInstant();
                if (start.isBefore(earliest)) continue;
                Instant blocks = start.plus(Duration.ofMinutes(dur + buf));
                int overlapping = 0, sameService = 0;
                for (var b : busy) if (((java.sql.Timestamp) b.get("starts_at")).toInstant().isBefore(blocks) && ((java.sql.Timestamp) b.get("blocks_until")).toInstant().isAfter(start)) { overlapping++; if (svc.get("id").equals(b.get("service_id"))) sameService++; }
                if (overlapping < stations && (serviceCap == null || sameService < serviceCap)) out.add(t);
                if (t.plusMinutes(step).isBefore(t)) break;   // past midnight
            }
        }
        return out;
    }

    /** The next days that still have a free time for the service (date, weekday, how many times, first and last time): what to offer when a customer has no date in mind. */
    public List<Map<String, Object>> nextDays(UUID tenantId, UUID serviceId, int days) {
        var svc = service(tenantId, serviceId);
        ZoneId tz = zone(tenantId);
        LocalDate d = LocalDate.now(tz);
        int ahead = ((Number) settings(tenantId).get("maxDaysAhead")).intValue();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i <= ahead && out.size() < days; i++, d = d.plusDays(1)) {
            var t = slotTimes(tenantId, svc, d, true, null);
            if (t.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.put("weekday", d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH));
            m.put("freeTimes", t.size());
            m.put("first", t.get(0).toString());
            m.put("last", t.get(t.size() - 1).toString());
            out.add(m);
        }
        return out;
    }

    // ---- booking --------------------------------------------------------------------------------------------------

    private static String number() {
        StringBuilder sb = new StringBuilder("B-");
        for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** Creates an appointment request for a free time. Two customers asking for the last free time at once: the second one is told it is gone. */
    @Transactional
    public Map<String, Object> book(UUID tenantId, UUID actor, BookReq r) {
        var svc = service(tenantId, r.serviceId());
        if (!Boolean.TRUE.equals(svc.get("isActive"))) throw BusinessException.badRequest("SERVICE_UNAVAILABLE", "This service is not available");
        String name = r.name() == null ? "" : r.name().trim();
        if (name.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Name is required").field("name");
        String phone = PhoneNormalizer.normalize(r.phone());
        if (phone == null || phone.replaceAll("\\D", "").length() < 9) throw BusinessException.badRequest("VALIDATION_ERROR", "A valid phone number is required").field("phone");
        LocalDate date = parseDate(r.date());
        LocalTime t = time(r.time(), "time");
        boolean fromCustomer = !"DASHBOARD".equals(r.source());
        if (fromCustomer && jdbc.sql("SELECT count(*) FROM commerce.bookings WHERE tenant_id = :t AND phone = :p AND created_at > now() - interval '1 day' AND status IN ('PENDING','CONFIRMED')").param("t", tenantId).param("p", phone).query(Long.class).single() >= 5)
            throw BusinessException.badRequest("TOO_MANY_BOOKINGS", "This number already has several appointments waiting: please call the shop to change them").field("phone");   // a stray script or a prank cannot fill the shop's day
        String where = r.locationMode() == null || r.locationMode().isBlank() ? null : r.locationMode();
        String offered = (String) svc.get("locationMode");
        if (where == null) where = "BOTH".equals(offered) ? "SHOP" : offered;
        if (!LOCATIONS.contains(where) || ("SHOP".equals(offered) && !"SHOP".equals(where)) || ("HOME".equals(offered) && !"HOME".equals(where))) throw BusinessException.badRequest("LOCATION_UNAVAILABLE", "This service is not offered there").field("locationMode");
        String address = r.address() == null ? "" : r.address().trim();
        Map<String, String> given = answers(svc, r.answers(), where, address);   // the service's own questions first, then the address of a home visit
        if ("HOME".equals(where) && address.length() < 6) throw BusinessException.badRequest("VALIDATION_ERROR", "The address is required for a home visit").field("address");
        if (address.length() > 500) throw BusinessException.badRequest("VALIDATION_ERROR", "Too long").field("address");
        Extras ex = extras(tenantId, svc, r.addonIds());
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:k AS text), 0))").param("k", tenantId + "|booking").query().listOfRows();
        if (!slotTimes(tenantId, svc, date, fromCustomer, null, ex.minutes()).contains(t)) throw BusinessException.badRequest("SLOT_TAKEN", "This time is not available any more").field("time");
        ZoneId tz = zone(tenantId);
        Instant start = ZonedDateTime.of(date, t, tz).toInstant();
        int dur = ((Number) svc.get("durationMinutes")).intValue() + ex.minutes(), buf = ((Number) svc.get("bufferMinutes")).intValue();
        boolean auto = Boolean.TRUE.equals(settings(tenantId).get("autoConfirm")) || !fromCustomer;
        UUID customer = customers.ensureCustomer(tenantId, null, new StoreCustomerService.Contact(name, phone, null, null));
        jdbc.sql("UPDATE commerce.customers SET source = 'BOOKING' WHERE id = :i AND source = 'ORDER' AND NOT EXISTS (SELECT 1 FROM commerce.orders o WHERE o.customer_id = :i)").param("i", customer).update();
        String number = number();
        UUID id = jdbc.sql("""
                INSERT INTO commerce.bookings (tenant_id, booking_number, service_id, service_name, customer_id, customer_name, phone, starts_at, ends_at, blocks_until, price_minor, currency, status, source, conversation_id, note, answers, addons, location_mode, address)
                VALUES (:t, :n, :s, :sn, :c, :cn, :p, :st, :en, :bl, :pr, :cur, :status, :src, :conv, :note, CAST(:ans AS jsonb), CAST(:add AS jsonb), :loc, :addr) RETURNING id
                """).param("t", tenantId).param("n", number).param("s", r.serviceId()).param("sn", svc.get("name")).param("c", customer).param("cn", name).param("p", phone).param("st", java.sql.Timestamp.from(start))
                .param("en", java.sql.Timestamp.from(start.plus(Duration.ofMinutes(dur)))).param("bl", java.sql.Timestamp.from(start.plus(Duration.ofMinutes(dur + buf)))).param("pr", ((Number) svc.get("priceMinor")).longValue() + ex.priceMinor()).param("cur", svc.get("currency"))
                .param("status", auto ? "CONFIRMED" : "PENDING").param("src", r.source() == null ? "AI_CHAT" : r.source()).param("conv", r.conversationId()).param("note", r.note() == null || r.note().isBlank() ? null : r.note().trim())
                .param("ans", jsonOf(given)).param("add", jsonOf(ex.items())).param("loc", where).param("addr", address.isEmpty() ? null : address)
                .query(UUID.class).single();
        if (!auto) notifyOwners(tenantId, id, number, name, (String) svc.get("name"), start, tz);
        return get(tenantId, id);
    }

    private static String jsonOf(Object o) {
        try { return JSON.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public Map<String, Object> get(UUID tenantId, UUID id) {
        return view(jdbc.sql(SELECT + " WHERE b.id = :i AND b.tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Appointment not found")), zone(tenantId));
    }

    private static final String SELECT = "SELECT b.id, b.booking_number, b.service_id, b.service_name, b.customer_id, b.customer_name, b.phone, b.starts_at, b.ends_at, b.price_minor, b.currency, b.status, b.source, b.note, b.decline_reason, b.created_at, b.conversation_id, b.answers::text AS answers_json, b.addons::text AS addons_json, b.location_mode, b.address FROM commerce.bookings b";

    private Map<String, Object> view(Map<String, Object> r, ZoneId tz) {
        Map<String, Object> m = Rows.camel(r);
        Instant s = ((java.sql.Timestamp) r.get("starts_at")).toInstant(), e = ((java.sql.Timestamp) r.get("ends_at")).toInstant();
        m.put("startsAt", s.toString());
        m.put("endsAt", e.toString());
        var zs = s.atZone(tz);
        m.put("date", zs.toLocalDate().toString());
        m.put("time", zs.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
        m.put("endTime", e.atZone(tz).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
        m.put("weekday", zs.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH));
        m.remove("conversationId");
        try { m.put("answers", JSON.readValue((String) m.remove("answersJson"), Map.class)); m.put("addons", JSON.readValue((String) m.remove("addonsJson"), List.class)); } catch (Exception ex) { m.put("answers", Map.of()); m.put("addons", List.of()); }
        return m;
    }

    public Map<String, Object> list(UUID tenantId, String from, String to, String status, String q, Page page) {
        ZoneId tz = zone(tenantId);
        Instant f = from == null || from.isBlank() ? Instant.now().minus(Duration.ofDays(1)) : parseDate(from).atStartOfDay(tz).toInstant();
        Instant tt = to == null || to.isBlank() ? f.plus(Duration.ofDays(60)) : parseDate(to).plusDays(1).atStartOfDay(tz).toInstant();
        String st = status == null || status.isBlank() ? null : status.toUpperCase();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        String where = " WHERE b.tenant_id = :t AND b.starts_at >= :f AND b.starts_at < :to AND (CAST(:st AS varchar) IS NULL OR b.status = CAST(:st AS varchar)) AND (CAST(:q AS varchar) IS NULL OR lower(b.customer_name) LIKE CAST(:q AS varchar) OR b.phone LIKE CAST(:q AS varchar) OR lower(b.booking_number) LIKE CAST(:q AS varchar))";
        long total = jdbc.sql("SELECT count(*) FROM commerce.bookings b" + where).param("t", tenantId).param("f", java.sql.Timestamp.from(f)).param("to", java.sql.Timestamp.from(tt)).param("st", st).param("q", like).query(Long.class).single();
        var rows = jdbc.sql(SELECT + where + " ORDER BY b.starts_at LIMIT :l OFFSET :o").param("t", tenantId).param("f", java.sql.Timestamp.from(f)).param("to", java.sql.Timestamp.from(tt)).param("st", st).param("q", like).param("l", page.pageSize()).param("o", page.offset()).query().listOfRows();
        return page.wrap(rows.stream().map(r -> view(r, tz)).toList(), total);
    }

    /** Counts for the dashboard badge and the page header. */
    public Map<String, Object> summary(UUID tenantId) {
        var r = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'PENDING' AND starts_at >= now()) AS pending,
                       count(*) FILTER (WHERE status = 'CONFIRMED' AND starts_at >= now() AND starts_at < now() + interval '1 day') AS next24,
                       count(*) FILTER (WHERE status = 'CONFIRMED' AND starts_at >= now()) AS upcoming
                FROM commerce.bookings WHERE tenant_id = :t
                """).param("t", tenantId).query().singleRow();
        return Rows.camel(r);
    }

    /** What a customer (identified by their phone) has booked and not yet had: used by the assistant to answer «ميعادي امتى؟». */
    public List<Map<String, Object>> upcomingForPhone(UUID tenantId, String phone) {
        String p = PhoneNormalizer.normalize(phone);
        if (p == null) return List.of();
        ZoneId tz = zone(tenantId);
        return jdbc.sql(SELECT + " WHERE b.tenant_id = :t AND b.phone = :p AND b.status IN ('PENDING','CONFIRMED') AND b.starts_at >= now() ORDER BY b.starts_at LIMIT 10").param("t", tenantId).param("p", p).query().listOfRows().stream().map(r -> view(r, tz)).toList();
    }

    public Map<String, Object> byNumberAndPhone(UUID tenantId, String number, String phone) {
        String p = PhoneNormalizer.normalize(phone);
        var row = jdbc.sql(SELECT + " WHERE b.tenant_id = :t AND upper(b.booking_number) = upper(:n) AND b.phone = :p").param("t", tenantId).param("n", number == null ? "" : number.trim()).param("p", p == null ? "" : p).query().listOfRows().stream().findFirst();
        return row.map(r -> view(r, zone(tenantId))).orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "No appointment with this number and phone"));
    }

    // ---- the shop's decisions and changes --------------------------------------------------------------------------

    private Map<String, Object> raw(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT id, booking_number, status, service_id, service_name, customer_name, phone, starts_at, ends_at, conversation_id FROM commerce.bookings WHERE id = :i AND tenant_id = :t FOR UPDATE").param("i", id).param("t", tenantId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Appointment not found"));
    }

    @Transactional
    public Map<String, Object> confirm(UUID tenantId, UUID id) {
        var b = raw(tenantId, id);
        if (!"PENDING".equals(b.get("status"))) throw BusinessException.badRequest("INVALID_STATE", "Only a waiting request can be confirmed");
        if (((java.sql.Timestamp) b.get("starts_at")).toInstant().isBefore(Instant.now())) throw BusinessException.badRequest("INVALID_STATE", "This time has already passed");
        jdbc.sql("UPDATE commerce.bookings SET status = 'CONFIRMED', updated_at = now() WHERE id = :i").param("i", id).update();
        tellCustomer(tenantId, b, "CONFIRMED", null);
        return get(tenantId, id);
    }

    @Transactional
    public Map<String, Object> decline(UUID tenantId, UUID id, String reason) {
        var b = raw(tenantId, id);
        if (!"PENDING".equals(b.get("status"))) throw BusinessException.badRequest("INVALID_STATE", "Only a waiting request can be declined");
        jdbc.sql("UPDATE commerce.bookings SET status = 'DECLINED', decline_reason = :r, updated_at = now() WHERE id = :i").param("r", clean(reason, 300, "reason")).param("i", id).update();
        tellCustomer(tenantId, b, "DECLINED", reason);
        return get(tenantId, id);
    }

    /** A customer cannot cancel or move an appointment that starts sooner than the shop's deadline; the shop itself always can. */
    private void deadline(UUID tenantId, Map<String, Object> b) {
        int hours = ((Number) settings(tenantId).get("cancelDeadlineHours")).intValue();
        if (hours > 0 && ((java.sql.Timestamp) b.get("starts_at")).toInstant().isBefore(Instant.now().plus(Duration.ofHours(hours))))
            throw BusinessException.badRequest("TOO_LATE_TO_CHANGE", "This appointment starts in less than " + hours + " hours: please call the shop to change it");
    }

    /** By the shop (the customer is told) or by the customer (the shop is told). */
    @Transactional
    public Map<String, Object> cancel(UUID tenantId, UUID id, boolean byCustomer, String reason) {
        var b = raw(tenantId, id);
        if (!List.of("PENDING", "CONFIRMED").contains(b.get("status"))) throw BusinessException.badRequest("INVALID_STATE", "This appointment can no longer be cancelled");
        if (byCustomer) deadline(tenantId, b);
        jdbc.sql("UPDATE commerce.bookings SET status = 'CANCELLED', decline_reason = :r, updated_at = now() WHERE id = :i").param("r", clean(reason, 300, "reason")).param("i", id).update();
        if (byCustomer) notifyOwnersText(tenantId, "BOOKING_CANCELLED", b, true);
        else tellCustomer(tenantId, b, "CANCELLED", reason);
        return get(tenantId, id);
    }

    @Transactional
    public Map<String, Object> finish(UUID tenantId, UUID id, boolean noShow) {
        var b = raw(tenantId, id);
        if (!"CONFIRMED".equals(b.get("status"))) throw BusinessException.badRequest("INVALID_STATE", "Only a confirmed appointment can be completed");
        jdbc.sql("UPDATE commerce.bookings SET status = :s, updated_at = now() WHERE id = :i").param("s", noShow ? "NO_SHOW" : "COMPLETED").param("i", id).update();
        return get(tenantId, id);
    }

    /** Moves an appointment to another free time. A customer's change goes back to the shop for confirmation unless the shop confirms automatically; the shop's own change keeps the status and tells the customer. */
    @Transactional
    public Map<String, Object> reschedule(UUID tenantId, UUID id, String date, String time, boolean byCustomer) {
        var b = raw(tenantId, id);
        if (!List.of("PENDING", "CONFIRMED").contains(b.get("status"))) throw BusinessException.badRequest("INVALID_STATE", "This appointment can no longer be changed");
        var svc = service(tenantId, (UUID) b.get("service_id"));
        LocalDate d = parseDate(date);
        LocalTime t = time(time, "time");
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:k AS text), 0))").param("k", tenantId + "|booking").query().listOfRows();
        int ownMinutes = (int) Duration.between(((java.sql.Timestamp) b.get("starts_at")).toInstant(), ((java.sql.Timestamp) b.get("ends_at")).toInstant()).toMinutes();   // the add-ons of this booking are part of its length
        int extra = Math.max(0, ownMinutes - ((Number) svc.get("durationMinutes")).intValue());
        if (byCustomer) deadline(tenantId, b);
        if (!slotTimes(tenantId, svc, d, byCustomer, id, extra).contains(t)) throw BusinessException.badRequest("SLOT_TAKEN", "This time is not available").field("time");
        ZoneId tz = zone(tenantId);
        Instant start = ZonedDateTime.of(d, t, tz).toInstant();
        int dur = ownMinutes, buf = ((Number) svc.get("bufferMinutes")).intValue();
        boolean auto = Boolean.TRUE.equals(settings(tenantId).get("autoConfirm"));
        String status = byCustomer && !auto ? "PENDING" : (String) b.get("status");
        jdbc.sql("UPDATE commerce.bookings SET starts_at = :s, ends_at = :e, blocks_until = :bl, status = :st, reminder_sent_at = NULL, updated_at = now() WHERE id = :i")
                .param("s", java.sql.Timestamp.from(start)).param("e", java.sql.Timestamp.from(start.plus(Duration.ofMinutes(dur)))).param("bl", java.sql.Timestamp.from(start.plus(Duration.ofMinutes(dur + buf)))).param("st", status).param("i", id).update();
        if (byCustomer) notifyOwnersText(tenantId, "BOOKING_CHANGED", raw(tenantId, id), false);
        else tellCustomer(tenantId, raw(tenantId, id), "RESCHEDULED", null);
        return get(tenantId, id);
    }

    // ---- telling people -------------------------------------------------------------------------------------------

    private boolean ar(UUID tenantId) { return jdbc.sql("SELECT default_locale FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("ar").startsWith("ar"); }

    private String when(UUID tenantId, Map<String, Object> b) {
        var z = ((java.sql.Timestamp) b.get("starts_at")).toInstant().atZone(zone(tenantId));
        return z.toLocalDate() + (ar(tenantId) ? " الساعة " : " at ") + z.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private List<UUID> owners(UUID tenantId) {
        return jdbc.sql("""
                SELECT DISTINCT m.user_id FROM core.user_tenant_memberships m JOIN core.membership_roles mr ON mr.membership_id = m.id
                JOIN core.role_permissions rp ON rp.role_id = mr.role_id JOIN core.permissions p ON p.id = rp.permission_id
                WHERE m.tenant_id = :t AND m.status = 'ACTIVE' AND p.code = 'booking.manage'
                """).param("t", tenantId).query(UUID.class).list();
    }

    private void notifyOwners(UUID tenantId, UUID id, String number, String customer, String service, Instant start, ZoneId tz) {
        try {
            boolean ar = ar(tenantId);
            String when = start.atZone(tz).toLocalDate() + " " + start.atZone(tz).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
            String title = ar ? "طلب حجز جديد" : "New appointment request", body = ar ? customer + " - " + service + " - " + when + " (" + number + ")" : customer + " - " + service + " - " + when + " (" + number + ")";
            for (UUID u : owners(tenantId)) {
                notifications.notify(u, tenantId, "BOOKING_REQUESTED", title, body, "{\"type\":\"BOOKING_REQUESTED\",\"bookingId\":\"" + id + "\"}", false);
                notifications.push(u, tenantId, title, body, "{\"type\":\"BOOKING_REQUESTED\",\"bookingId\":\"" + id + "\"}");
            }
            notifications.dispatchSoon();
        } catch (RuntimeException e) {
            log.warn("Could not notify the shop about appointment {}: {}", number, e.getMessage());
        }
    }

    private void notifyOwnersText(UUID tenantId, String type, Map<String, Object> b, boolean cancelled) {
        try {
            boolean ar = ar(tenantId);
            String title = cancelled ? (ar ? "تم إلغاء حجز" : "Appointment cancelled") : (ar ? "عميل غيّر ميعاده" : "Appointment changed by the customer");
            String body = b.get("customer_name") + " - " + b.get("service_name") + " - " + when(tenantId, b) + " (" + b.get("booking_number") + ")";
            for (UUID u : owners(tenantId)) {
                notifications.notify(u, tenantId, type, title, body, "{\"type\":\"" + type + "\",\"bookingId\":\"" + b.get("id") + "\"}", false);
                notifications.push(u, tenantId, title, body, "{\"type\":\"" + type + "\"}");
            }
            notifications.dispatchSoon();
        } catch (RuntimeException e) {
            log.warn("Could not notify the shop about a change of appointment: {}", e.getMessage());
        }
    }

    /** The customer hears the shop's decision in the chat they booked in (when that chat can still be written to). */
    void tellCustomer(UUID tenantId, Map<String, Object> b, String what, String reason) {
        if (b.get("conversation_id") == null) return;
        try {
            var conv = jdbc.sql("SELECT channel, external_id FROM chat.conversations WHERE id = :c AND tenant_id = :t").param("c", b.get("conversation_id")).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
            if (conv == null) return;
            boolean ar = ar(tenantId);
            String w = when(tenantId, b), svc = String.valueOf(b.get("service_name")), no = String.valueOf(b.get("booking_number"));
            String text = switch (what) {
                case "CONFIRMED" -> ar ? "✅ تم تأكيد حجزك: " + svc + " يوم " + w + " (رقم " + no + "). منتظرينك!" : "✅ Your appointment is confirmed: " + svc + " on " + w + " (" + no + "). See you!";
                case "DECLINED" -> ar ? "للأسف مقدرناش نأكد حجز " + svc + " يوم " + w + (reason == null || reason.isBlank() ? "" : " (" + reason + ")") + ". تحب نختار ميعاد تاني؟" : "Sorry, we could not confirm " + svc + " on " + w + (reason == null || reason.isBlank() ? "" : " (" + reason + ")") + ". Would you like another time?";
                case "RESCHEDULED" -> ar ? "تم تغيير ميعادك: " + svc + " بقى يوم " + w + " (رقم " + no + ")." : "Your appointment was moved: " + svc + " is now on " + w + " (" + no + ").";
                default -> ar ? "تم إلغاء حجزك: " + svc + " يوم " + w + " (رقم " + no + ")." : "Your appointment was cancelled: " + svc + " on " + w + " (" + no + ").";
            };
            var res = delivery.send(tenantId, (String) conv.get("channel"), (String) conv.get("external_id"), text, null);
            if ("SAVED".equals(res.delivery()))   // the website chat: the message waits in the chat for the visitor
                jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'assistant', :m)").param("c", b.get("conversation_id")).param("m", text).update();
        } catch (RuntimeException e) {
            log.info("Could not tell the customer about appointment {}: {}", b.get("booking_number"), e.getMessage());
        }
    }

    /** A request nobody answered within the shop's limit stops holding the time: it is declined and the customer is told (only for shops that set a limit). */
    @Scheduled(fixedDelay = 300_000, initialDelay = 240_000)
    public void expirePending() {
        for (var b : jdbc.sql("""
                UPDATE commerce.bookings b SET status = 'DECLINED', decline_reason = 'No answer in time', updated_at = now()
                FROM commerce.booking_settings s WHERE s.tenant_id = b.tenant_id AND s.pending_expires_minutes IS NOT NULL AND b.status = 'PENDING' AND b.created_at < now() - make_interval(mins => s.pending_expires_minutes)
                RETURNING b.id, b.tenant_id, b.booking_number, b.service_name, b.customer_name, b.phone, b.starts_at, b.conversation_id
                """).query().listOfRows()) {
            try { tellCustomer((UUID) b.get("tenant_id"), b, "DECLINED", "no answer in time"); } catch (RuntimeException e) { log.info("Expired request {} not announced: {}", b.get("booking_number"), e.getMessage()); }
        }
    }

    /** A reminder about two hours before a confirmed appointment (only where the chat can still be written to; nothing is sent as a paid message). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 180_000)
    public void remind() {
        for (var b : jdbc.sql("""
                SELECT b.id, b.tenant_id, b.booking_number, b.service_name, b.customer_name, b.phone, b.starts_at, b.conversation_id FROM commerce.bookings b
                WHERE b.status = 'CONFIRMED' AND b.reminder_sent_at IS NULL AND b.conversation_id IS NOT NULL AND b.starts_at > now() AND b.starts_at <= now() + interval '2 hours'
                  AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = b.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED')) LIMIT 100
                """).query().listOfRows()) {
            UUID tenantId = (UUID) b.get("tenant_id");
            jdbc.sql("UPDATE commerce.bookings SET reminder_sent_at = now() WHERE id = :i").param("i", b.get("id")).update();
            try {
                var conv = jdbc.sql("SELECT channel, external_id FROM chat.conversations WHERE id = :c").param("c", b.get("conversation_id")).query().listOfRows().stream().findFirst().orElse(null);
                if (conv == null || "WEB".equals(conv.get("channel"))) continue;
                boolean ar = ar(tenantId);
                String text = ar ? "تذكير: ميعادك النهاردة " + when(tenantId, b) + " - " + b.get("service_name") + ". منتظرينك 🌸" : "Reminder: your appointment is today " + when(tenantId, b) + " - " + b.get("service_name") + ". See you 🌸";
                delivery.send(tenantId, (String) conv.get("channel"), (String) conv.get("external_id"), text, null);
            } catch (RuntimeException e) {
                log.info("Reminder for {} not sent: {}", b.get("booking_number"), e.getMessage());
            }
        }
    }
}
