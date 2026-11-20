package com.platform.chat;

import com.platform.booking.BookingService;
import com.platform.chat.AiProvider.ToolDef;
import com.platform.commerce.StoreCustomerService;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the owner can switch on for the assistant: taking appointments for the shop's services (BOOKINGS) and saving the customer as a lead (LEADS). These tools and the instructions that go with
 * them exist only for a shop that chose them; a shop that did not keeps the assistant exactly as it was.
 */
@Component
public class ServiceTools {
    static final Set<String> NAMES = Set.of("list_services", "available_slots", "book_appointment", "my_bookings", "cancel_booking", "reschedule_booking", "save_lead");

    private final JdbcClient jdbc;
    private final BookingService bookings;
    private final StoreCustomerService customers;

    public ServiceTools(JdbcClient jdbc, BookingService bookings, StoreCustomerService customers) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.customers = customers;
    }

    boolean handles(String tool) { return NAMES.contains(tool); }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> str(String d) { return obj("type", "STRING", "description", d); }

    /** The tools this shop's goals add. */
    List<ToolDef> defs(Set<String> goals) {
        List<ToolDef> out = new ArrayList<>();
        if (goals.contains("BOOKINGS")) {
            out.add(new ToolDef("list_services", "The shop's services with their ids, duration and price, the opening hours, and today's date in the shop's timezone. Use it when the customer asks what the shop offers or what a service costs.",
                    obj("type", "OBJECT", "properties", obj())));
            out.add(new ToolDef("available_slots", "Free appointment times for a service. With a date (YYYY-MM-DD) it returns that day's free start times; without one it returns the next days that have free times. Also tells today's date: call it whenever the customer says a relative day (today, tomorrow, Friday). Offer only times it returns.",
                    obj("type", "OBJECT", "properties", obj("serviceId", str("Service id from list_services or the SERVICES list"), "date", str("YYYY-MM-DD, optional"), "addonIds", obj("type", "ARRAY", "description", "Ids of the extras (add-ons) the customer chose for this service, from list_services; leave out when none", "items", obj("type", "STRING"))), "required", List.of("serviceId"))));
            out.add(new ToolDef("book_appointment", "Create the appointment request AFTER the customer confirmed the service, day, time and their phone number in this chat. Needs a free time from available_slots of this turn or a recent one. Say the request is registered; it is confirmed only if the result says CONFIRMED.",
                    obj("type", "OBJECT", "properties", obj("serviceId", str("Service id"), "date", str("YYYY-MM-DD"), "time", str("HH:mm, 24 hours, one of the free times"), "name", str("The customer's first name"), "phone", str("The customer's mobile number"), "note", str("What the customer asked for, in their words (optional)"), "addonIds", obj("type", "ARRAY", "description", "Ids of the extras (add-ons) the customer chose for this service, from list_services; leave out when none", "items", obj("type", "STRING")), "answers", obj("type", "OBJECT", "description", "The customer's answers to the service's questions (the service's questions list in list_services / SERVICES): key to answer"), "locationMode", str("SHOP or HOME, only for a service offered in both"), "address", str("The customer's address, only for a home visit")),
                            "required", List.of("serviceId", "date", "time", "name", "phone"))));
            out.add(new ToolDef("my_bookings", "The customer's upcoming appointments. Use it when they ask when their appointment is or whether it is confirmed. The phone is optional when the chat already knows it.",
                    obj("type", "OBJECT", "properties", obj("phone", str("The customer's mobile number, optional")))));
            out.add(new ToolDef("cancel_booking", "Cancel the customer's appointment. Ask kindly why in one short question first, then call it with the appointment number and their phone.",
                    obj("type", "OBJECT", "properties", obj("bookingNumber", str("The appointment number, like B-XXXXXX"), "phone", str("The customer's mobile number"), "reason", str("Why, in a few words")), "required", List.of("bookingNumber"))));
            out.add(new ToolDef("reschedule_booking", "Move the customer's appointment to another free time (check available_slots first). Needs the appointment number and their phone.",
                    obj("type", "OBJECT", "properties", obj("bookingNumber", str("The appointment number"), "phone", str("The customer's mobile number"), "date", str("YYYY-MM-DD"), "time", str("HH:mm")), "required", List.of("bookingNumber", "date", "time"))));
        }
        if (goals.contains("LEADS"))
            out.add(new ToolDef("save_lead", "Save this customer in the shop's customer list so the shop's customer service can contact them. Call it with the first name as soon as you know it, and again with the phone number once they give it. interest: what they asked about or need, in a few words.",
                    obj("type", "OBJECT", "properties", obj("name", str("The customer's first name"), "phone", str("Their WhatsApp / mobile number (only when they gave it)"), "interest", str("What they are interested in or need, a few words")))));
        return out;
    }

    // ---- the instructions -----------------------------------------------------------------------------------------

    /** The owner's choices as instructions for the model (empty for the default shop). */
    String promptBlock(UUID tenantId, Set<String> goals) {
        if (goals.equals(ShopGoals.DEFAULT)) return "";
        StringBuilder p = new StringBuilder("\nWHAT THIS SHOP WANTS YOU TO DO (set by the owner; strict, these come with the RULES):\n");
        if (!goals.contains("ORDERS"))
            p.append("- PRODUCT ORDERS ARE OFF: this chat does not place product orders. Never call prepare_order, place_order or cancel_order, never ask for a delivery address or a payment method, and never say you can order something for them. Answer about the shop, its products and its services, and help with what is switched on below.\n");
        if (goals.contains("LEADS")) p.append("""
                - LEADS (strict): the owner wants every interested customer saved as a customer to message later.
                  NAME FIRST: in your FIRST reply of a chat, when you do not know their name yet (see WHAT YOU REMEMBER and the chat), answer what they asked and then ask their first name once, in one short warm question («اسم حضرتك إيه؟», «أقدر أعرف اسم حضرتك؟»). Never hold an answer back for it. Use the name afterwards, not in every message. If they skip it, carry on and ask once more later at most. Call save_lead with the name as soon as you have it.
                  PHONE AFTER THEY ARE SATISFIED: ask for their WhatsApp number only after they are satisfied: they got a clear answer about a product or a service, or they ask to order or to book, or they say thanks or ok. Say why in one short, natural sentence: customer service will contact them on it («محتاج رقم الواتساب بتاع حضرتك عشان خدمة العملاء تتواصل معاك», «ممكن رقمك على الواتساب عشان فريق خدمة العملاء يكلمك؟»). Do not ask about offers or marketing. Never in the first messages, never before answering, and if they decline do not ask again. Then call save_lead with name, phone and interest (what they asked about or need, a few words).
                  When you collect name and phone for an order or an appointment, call save_lead as soon as you have both, before preparing the order or booking. Never ask for card numbers, passwords or an address that is not needed.
                  IF COLLECTING IS ALL THIS SHOP ASKS OF YOU (no orders, no appointments): be a helpful, smart first point of contact: answer what you can from the shop's data, find out gently what the customer needs and how urgent it is, get the name early and the number once they are satisfied, then tell them customer service will contact them. Never promise a price, a time, availability or an outcome the shop's data does not state: customer service will confirm those.
                """);
        if (goals.contains("BOOKINGS")) {
            p.append("""
                - APPOINTMENTS (strict): this shop sells services by appointment (see SERVICES below). To book:
                  1) Find the service they want (SERVICES list; list_services for details). If they are unsure, describe the services briefly and ask which. 2) Ask which day suits them. Whenever they say a relative day (today, tomorrow, Friday) or have none, call available_slots (it tells today's date) and offer the days or times it returns. Offer ONLY times returned by available_slots, never invent or guess a time or say the shop is open at some time. If the day has none, say so and offer the next days from the result.
                  3) Get their first name and mobile number (skip what you already have). 4) Confirm in one short message: service, day (weekday and date), time, price (from the list) and their number, and ask them to confirm. Only after they say yes call book_appointment. 5) After booking: give the appointment number. If the result says needsShopConfirmation, say the request is registered and the shop will confirm it shortly (you will not say it is confirmed); if it says CONFIRMED, say it is confirmed. If the time was just taken, apologise and offer the next free times.
                  6) For «ميعادي امتى؟» use my_bookings; to move or cancel use reschedule_booking or cancel_booking with the number and their phone (cancel: ask why first, kindly). Prices and durations come only from the SERVICES list or tools; never promise deposits, discounts, a stylist or staff member, or a place the shop's data does not state.
                """);
            var services = bookings.services(tenantId, true);
            if (!services.isEmpty()) {
                p.append("\nSERVICES (this shop's own list: names, ids, duration and price are real; id in brackets):\n");
                for (var s : services) {
                    String desc = s.get("description") == null ? "" : String.valueOf(s.get("description")).replaceAll("\\s+", " ").trim();
                    p.append("- ").append(s.get("name")).append(" [").append(s.get("id")).append("] - ").append(s.get("durationMinutes")).append(" min - ").append(major(((Number) s.get("priceMinor")).longValue())).append(' ').append(s.get("currency"))
                            .append(desc.isEmpty() ? "" : " - " + (desc.length() > 120 ? desc.substring(0, 120) : desc)).append(serviceExtras(s)).append('\n');
                }
            }
            var hours = bookings.hours(tenantId);
            if (!hours.isEmpty()) {
                p.append("OPENING HOURS (weekday 1 = Monday ... 7 = Sunday):");
                for (var h : hours) p.append(' ').append(h.get("weekday")).append(": ").append(h.get("opens")).append("-").append(h.get("closes")).append(';');
                p.append('\n');
            }
        }
        return p.toString();
    }

    /** What a service adds to the conversation: where it is done, the questions to ask, the extras on offer. */
    @SuppressWarnings("unchecked")
    private static String serviceExtras(Map<String, Object> s) {
        StringBuilder b = new StringBuilder();
        String where = String.valueOf(s.get("locationMode"));
        b.append(" - ").append("HOME".equals(where) ? "done at the customer's home (ask for the address)" : "BOTH".equals(where) ? "at the shop or at the customer's home (ask which; the address for a home visit)" : "done at the shop");
        if (s.get("serviceArea") != null && !String.valueOf(s.get("serviceArea")).isBlank()) b.append(" - area: ").append(String.valueOf(s.get("serviceArea")).replaceAll("\\s+", " "));
        var q = (List<Map<String, Object>>) s.get("requiredFields");
        if (!q.isEmpty()) { b.append(" - ASK BEFORE BOOKING (answers go in answers{key}): "); for (var f : q) b.append(f.get("label")).append(" [").append(f.get("key")).append(Boolean.FALSE.equals(f.get("required")) ? ", optional" : "").append("]; "); }
        var ex = (List<Map<String, Object>>) s.get("addons");
        if (!ex.isEmpty()) { b.append(" - EXTRAS you may offer (ids for addonIds): "); for (var a : ex) b.append(a.get("name")).append(" [").append(a.get("id")).append("] +").append(major(((Number) a.get("priceMinor")).longValue())).append(" and ").append(a.get("durationMinutes")).append(" min; "); }
        if (s.get("priceBeforeMinor") != null) b.append(" - was ").append(major(((Number) s.get("priceBeforeMinor")).longValue()));
        if (s.get("instructions") != null && !String.valueOf(s.get("instructions")).isBlank()) b.append(" - tell the customer: ").append(String.valueOf(s.get("instructions")).replaceAll("\\s+", " "));
        return b.toString();
    }

    private static String major(long minor) { return BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString(); }

    // ---- running a tool -------------------------------------------------------------------------------------------

    Map<String, Object> run(String tool, UUID tenantId, UUID conversationId, String channel, String externalId, Map<String, Object> a) {
        try {
            return switch (tool) {
                case "list_services" -> listServices(tenantId);
                case "available_slots" -> slots(tenantId, uuid(a.get("serviceId")), text(a.get("date")), ids(a.get("addonIds")));
                case "book_appointment" -> book(tenantId, conversationId, channel, externalId, a);
                case "my_bookings" -> mine(tenantId, conversationId, channel, externalId, text(a.get("phone")));
                case "cancel_booking" -> {
                    var b = bookings.byNumberAndPhone(tenantId, text(a.get("bookingNumber")), phoneOf(conversationId, channel, externalId, text(a.get("phone"))));
                    yield shown(bookings.cancel(tenantId, (UUID) b.get("id"), true, text(a.get("reason"))));
                }
                case "reschedule_booking" -> {
                    var b = bookings.byNumberAndPhone(tenantId, text(a.get("bookingNumber")), phoneOf(conversationId, channel, externalId, text(a.get("phone"))));
                    yield shown(bookings.reschedule(tenantId, (UUID) b.get("id"), text(a.get("date")), text(a.get("time")), true));
                }
                case "save_lead" -> saveLead(tenantId, conversationId, channel, externalId, a);
                default -> obj("error", "Unknown tool");
            };
        } catch (BusinessException e) {
            Map<String, Object> m = obj("error", e.getMessage());
            if ("SLOT_TAKEN".equals(e.getCode()) && a.get("serviceId") != null) {   // the time was just taken: the next free times come with the refusal
                try { m.put("nextFreeDays", bookings.nextDays(tenantId, uuid(a.get("serviceId")), 4)); } catch (RuntimeException ignored) { /* the refusal alone is enough */ }
            }
            return m;
        }
    }

    private static String text(Object o) { return o == null ? null : String.valueOf(o).trim(); }

    private static UUID uuid(Object o) {
        try { return UUID.fromString(String.valueOf(o).trim()); } catch (RuntimeException e) { throw BusinessException.badRequest("VALIDATION_ERROR", "serviceId must be an id from list_services or the SERVICES list"); }
    }

    private Map<String, Object> today(UUID tenantId) {
        ZoneId tz = bookings.zone(tenantId);
        LocalDate d = LocalDate.now(tz);
        return obj("today", d.toString(), "weekday", d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH), "timezone", tz.getId());
    }

    private Map<String, Object> listServices(UUID tenantId) {
        Map<String, Object> m = today(tenantId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (var s : bookings.services(tenantId, true)) list.add(obj("id", s.get("id"), "name", s.get("name"), "description", s.get("description"), "durationMinutes", s.get("durationMinutes"), "price", major(((Number) s.get("priceMinor")).longValue()), "currency", s.get("currency"),
                "category", s.get("categoryName"), "shortDescription", s.get("shortDescription"), "priceBefore", s.get("priceBeforeMinor") == null ? null : major(((Number) s.get("priceBeforeMinor")).longValue()),
                "location", s.get("locationMode"), "serviceArea", s.get("serviceArea"), "instructions", s.get("instructions"), "cancellationPolicy", s.get("cancellationPolicy"),
                "questions", s.get("requiredFields"), "extras", extrasOf(s)));
        m.put("services", list);
        m.put("openingHours", bookings.hours(tenantId));
        m.put("shopConfirmsEachRequest", !Boolean.TRUE.equals(bookings.settings(tenantId).get("autoConfirm")));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> ids(Object o) {
        List<UUID> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) { try { out.add(UUID.fromString(String.valueOf(x).trim())); } catch (RuntimeException e) { throw BusinessException.badRequest("VALIDATION_ERROR", "addonIds must be ids from list_services"); } }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> answers(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) m.forEach((k, v) -> { if (k != null && v != null) out.put(String.valueOf(k), String.valueOf(v)); });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extrasOf(Map<String, Object> service) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var a : (List<Map<String, Object>>) service.get("addons")) out.add(obj("id", a.get("id"), "name", a.get("name"), "price", major(((Number) a.get("priceMinor")).longValue()), "extraMinutes", a.get("durationMinutes")));
        return out;
    }

    private Map<String, Object> slots(UUID tenantId, UUID serviceId, String date, List<UUID> addonIds) {
        Map<String, Object> m = today(tenantId);
        bookings.service(tenantId, serviceId);
        if (date == null || date.isBlank()) { m.put("nextDays", bookings.nextDays(tenantId, serviceId, 5)); return m; }
        LocalDate d;
        try { d = LocalDate.parse(PhoneNormalizer.latinDigits(date)); } catch (RuntimeException e) { return obj("error", "date must be YYYY-MM-DD"); }
        var times = bookings.slots(tenantId, serviceId, d, true, addonIds);
        m.put("date", d.toString());
        m.put("weekday", d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH));
        m.put("freeTimes", times);
        if (times.isEmpty()) m.put("nextDays", bookings.nextDays(tenantId, serviceId, 4));
        return m;
    }

    private String phoneOf(UUID conv, String channel, String externalId, String given) {
        if (given != null && !given.isBlank()) return given;
        if ("WHATSAPP".equals(channel)) return externalId;
        return jdbc.sql("SELECT contact_phone FROM chat.conversations WHERE id = :c").param("c", conv).query(String.class).optional().orElse(null);
    }

    private Map<String, Object> shown(Map<String, Object> b) {
        return obj("bookingNumber", b.get("bookingNumber"), "service", b.get("serviceName"), "date", b.get("date"), "time", b.get("time"), "weekday", b.get("weekday"), "status", b.get("status"),
                "price", major(((Number) b.get("priceMinor")).longValue()), "currency", b.get("currency"), "extras", b.get("addons"));
    }

    private Map<String, Object> book(UUID tenantId, UUID conv, String channel, String externalId, Map<String, Object> a) {
        String phone = text(a.get("phone"));
        if (phone == null || phone.isBlank()) phone = phoneOf(conv, channel, externalId, null);
        var b = bookings.book(tenantId, null, new BookingService.BookReq(uuid(a.get("serviceId")), text(a.get("date")), text(a.get("time")), text(a.get("name")), phone, text(a.get("note")), "AI_CHAT", conv, ids(a.get("addonIds")), answers(a.get("answers")), text(a.get("locationMode")), text(a.get("address"))));
        rememberContact(conv, text(a.get("name")), phone);
        Map<String, Object> m = shown(b);
        m.put("needsShopConfirmation", "PENDING".equals(b.get("status")));
        return m;
    }

    private Map<String, Object> mine(UUID tenantId, UUID conv, String channel, String externalId, String phone) {
        String p = phoneOf(conv, channel, externalId, phone);
        if (p == null || p.isBlank()) return obj("error", "Ask the customer for the phone number they booked with");
        List<Map<String, Object>> list = new ArrayList<>();
        for (var b : bookings.upcomingForPhone(tenantId, p)) list.add(shown(b));
        return obj("appointments", list);
    }

    /** The name and phone the customer gave stay with the chat: the shop's inbox shows them and the next message is answered by name. */
    private void rememberContact(UUID conv, String name, String phone) {
        String p = phone == null ? null : PhoneNormalizer.normalize(phone);
        jdbc.sql("UPDATE chat.conversations SET display_name = coalesce(CAST(:n AS varchar), display_name), contact_phone = coalesce(CAST(:p AS varchar), contact_phone) WHERE id = :c")
                .param("n", name == null || name.isBlank() ? null : name).param("p", p).param("c", conv).update();
    }

    private Map<String, Object> saveLead(UUID tenantId, UUID conv, String channel, String externalId, Map<String, Object> a) {
        String name = text(a.get("name")), phone = text(a.get("phone")), interest = text(a.get("interest"));
        if ((name == null || name.isBlank()) && (phone == null || phone.isBlank())) return obj("error", "Give at least the name or the phone number");
        if (phone == null || phone.isBlank()) {   // WhatsApp already knows the number: the chat itself is the contact
            if ("WHATSAPP".equals(channel) && name != null) phone = externalId;
        }
        rememberContact(conv, name, phone == null || phone.isBlank() ? null : phone);
        if (phone == null || phone.isBlank()) return obj("saved", "name", "note", "Name noted. The customer is added to the shop's customers once they give their number.");
        String normalized = PhoneNormalizer.normalize(phone);
        if (normalized == null || normalized.replaceAll("\\D", "").length() < 9) return obj("error", "That phone number does not look right: ask the customer to write it again");
        UUID id = customers.ensureCustomer(tenantId, null, new StoreCustomerService.Contact(name == null || name.isBlank() ? null : name, normalized, null, null));
        jdbc.sql("UPDATE commerce.customers SET source = 'LEAD' WHERE id = :i AND source = 'ORDER' AND NOT EXISTS (SELECT 1 FROM commerce.orders o WHERE o.customer_id = :i)").param("i", id).update();
        if (interest != null && !interest.isBlank())
            jdbc.sql("UPDATE commerce.customers SET notes = left(coalesce(notes || E'\\n', '') || to_char(now(), 'YYYY-MM-DD') || ': ' || :n, 2000) WHERE id = :i AND coalesce(notes, '') NOT LIKE '%' || :n || '%'").param("n", interest.length() > 160 ? interest.substring(0, 160) : interest).param("i", id).update();
        jdbc.sql("UPDATE commerce.customers SET marketing_opt_in = TRUE WHERE id = :i AND source = 'LEAD'").param("i", id).update();   // a lead is the shop's to message; whoever writes STOP is still left alone
        return obj("saved", "customer");
    }
}
