package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditService;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Free re-confirmation on WhatsApp. The customer taps "Confirm on WhatsApp" after ordering, which opens WhatsApp with "تأكيد طلب #N" ready to send. Their message
 * opens WhatsApp's free 24-hour window, so everything here is a normal reply: we check the message comes from the number they gave us, show the order, and when they
 * agree the order is marked confirmed. No model is involved, so nothing can be invented.
 */
@Service
public class WhatsAppConfirmService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STOP = Pattern.compile("^(ايقاف|وقف|الغاء الاشتراك|الغاء|stop|unsubscribe|cancel)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK = Pattern.compile("^(?:تاكيد|confirm)\\s*(?:طلب|الطلب|الاوردر|order)?\\s*#?\\s*([a-z0-9][a-z0-9-]{2,40})$", Pattern.CASE_INSENSITIVE);

    private final JdbcClient jdbc;
    private final ChatService chat;
    private final AuditService audit;

    public WhatsAppConfirmService(JdbcClient jdbc, ChatService chat, AuditService audit) {
        this.jdbc = jdbc;
        this.chat = chat;
        this.audit = audit;
    }

    static String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    /** Same phone, whatever the country-code style (+20 10…, 010…): compare the last ten digits. */
    static boolean samePhone(String a, String b) {
        String x = digits(a), y = digits(b);
        return x.length() >= 9 && y.length() >= 9 && x.substring(x.length() - 9).equals(y.substring(y.length() - 9));
    }

    /** The reply to send, or empty when this message has nothing to do with confirming an order (the assistant then answers as usual). */
    public Optional<String> handle(UUID tenantId, String sender, String text) {
        String n = text.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').trim();
        boolean arabic = text.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF);
        if (STOP.matcher(n).matches()) return Optional.of(optOut(tenantId, sender, arabic));
        if (n.toUpperCase().startsWith("CONFIRM:")) return Optional.of(confirmNow(tenantId, sender, n.substring(8).trim(), true));   // the customer tapped the Confirm button
        Matcher m = ASK.matcher(n);
        if (m.matches()) return Optional.of(ask(tenantId, sender, m.group(1), arabic));
        UUID conv = chat.conversationId(tenantId, "WHATSAPP", sender, null);
        var pending = jdbc.sql("SELECT pending::text FROM chat.conversations WHERE id = :c").param("c", conv).query(String.class).optional().orElse(null);
        if (pending == null || !ChatService.isConfirmation(text)) return Optional.empty();
        try {
            JsonNode p = JSON.readTree(pending);
            if (!p.hasNonNull("waConfirm")) return Optional.empty();
            return Optional.of(confirm(tenantId, conv, UUID.fromString(p.get("waConfirm").asText()), sender, arabic));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The customer wrote "stop": no more WhatsApp messages about their open orders. */
    private String optOut(UUID tenantId, String sender, boolean arabic) {
        String last9 = digits(sender);
        last9 = last9.substring(Math.max(0, last9.length() - 9));
        jdbc.sql("UPDATE commerce.orders SET whatsapp_opt_in_at = NULL WHERE tenant_id = :t AND whatsapp_confirmed_at IS NULL AND right(regexp_replace(coalesce(whatsapp_number, ''), '\\D', '', 'g'), 9) = :d").param("t", tenantId).param("d", last9).update();
        jdbc.sql("UPDATE commerce.customers SET whatsapp_opt_in_at = NULL WHERE tenant_id = :t AND right(regexp_replace(coalesce(whatsapp_number, ''), '\\D', '', 'g'), 9) = :d").param("t", tenantId).param("d", last9).update();
        return arabic ? "تم إيقاف رسائل واتساب من هذا المتجر. شكراً لك 🌸" : "WhatsApp messages from this shop are switched off. Thank you.";
    }

    /** The customer tapped Confirm (or we are sure they agree): confirm the order now and say so, with the order summary. */
    String confirmNow(UUID tenantId, String sender, String orderNumber, boolean arabic) {
        var o = jdbc.sql("SELECT id, status, whatsapp_number, whatsapp_confirmed_at, order_number FROM commerce.orders WHERE tenant_id = :t AND upper(order_number) = upper(:n)")
                .param("t", tenantId).param("n", orderNumber).query().listOfRows().stream().findFirst().orElse(null);
        if (o == null || !samePhone((String) o.get("whatsapp_number"), sender))
            return arabic ? "لم أجد طلبًا بهذا الرقم مرتبطًا برقم واتساب هذا." : "I could not find that order for this WhatsApp number.";
        if (o.get("whatsapp_confirmed_at") != null) return arabic ? "طلبك رقم " + o.get("order_number") + " متأكد بالفعل ✅ شكراً لك." : "Order " + o.get("order_number") + " is already confirmed ✅ Thank you.";
        if (!java.util.List.of("REQUESTED", "PREPARING").contains(o.get("status"))) return arabic ? "هذا الطلب لا يمكن تأكيده الآن." : "This order can no longer be confirmed.";
        UUID id = (UUID) o.get("id");
        String summary = describe(id, arabic);
        jdbc.sql("UPDATE commerce.orders SET confirmed_at = coalesce(confirmed_at, now()), whatsapp_confirmed_at = now(), updated_at = now() WHERE id = :o AND tenant_id = :t").param("o", id).param("t", tenantId).update();
        audit.record(null, tenantId, "ORDER_CONFIRMED_WHATSAPP", "order", id, null);
        return (arabic ? "تم تأكيد طلبك رقم " + o.get("order_number") + " ✅\n" : "Order " + o.get("order_number") + " is confirmed ✅\n") + summary
                + (arabic ? "\nهنجهزه ونتواصل معاك للتسليم. شكراً لثقتك 🌸" : "\nWe will prepare it and contact you for delivery. Thank you!");
    }

    /** Items, total and address of an order as plain text. */
    private String describe(UUID orderId, boolean arabic) {
        var o = jdbc.sql("SELECT total_minor, currency, area, shipping_address_snapshot::text AS addr FROM commerce.orders WHERE id = :o").param("o", orderId).query().listOfRows().get(0);
        StringBuilder items = new StringBuilder();
        for (var it : jdbc.sql("SELECT product_name_snapshot, variant_name_snapshot, quantity FROM commerce.order_items WHERE order_id = :o").param("o", orderId).query().listOfRows())
            items.append("• ").append(it.get("product_name_snapshot")).append(it.get("variant_name_snapshot") == null ? "" : " (" + it.get("variant_name_snapshot") + ")").append(" × ").append(it.get("quantity")).append("\n");
        String address = String.valueOf(o.get("area") == null ? "" : o.get("area"));
        try { JsonNode a = JSON.readTree((String) o.get("addr")); address = (a.path("addressLine1").asText("") + " " + address).trim(); } catch (Exception ignored) { /* the area alone is enough */ }
        double total = ((Number) o.get("total_minor")).longValue() / 100.0;
        String tot = (total == Math.rint(total) ? String.valueOf((long) total) : String.format(java.util.Locale.ROOT, "%.2f", total)) + " " + ("EGP".equals(o.get("currency")) ? (arabic ? "جنيه" : "EGP") : o.get("currency"));
        return items + (arabic ? "الإجمالي: " + tot + "\nالعنوان: " + address : "Total: " + tot + "\nAddress: " + address);
    }

    /** The "please confirm" message used by the automatic follow-up when the customer's WhatsApp window is open (a normal, free message). */
    public Optional<String> askText(UUID tenantId, String sender, String orderNumber) { return Optional.of(ask(tenantId, sender, orderNumber, true)); }

    private String ask(UUID tenantId, String sender, String orderNumber, boolean arabic) {
        var o = jdbc.sql("SELECT id, status, whatsapp_number, whatsapp_confirmed_at, customer_name_snapshot, total_minor, currency, area, shipping_address_snapshot::text AS addr, eta_min_days, eta_max_days, order_number "
                + "FROM commerce.orders WHERE tenant_id = :t AND upper(order_number) = upper(:n)").param("t", tenantId).param("n", orderNumber).query().listOfRows().stream().findFirst().orElse(null);
        // The same answer for "no such order" and "not your order", so nobody can probe order numbers.
        if (o == null || !samePhone((String) o.get("whatsapp_number"), sender))
            return arabic ? "لم أجد طلبًا بهذا الرقم مرتبطًا برقم واتساب هذا. اكتب لنا رقم الطلب الذي وصلك." : "I could not find that order for this WhatsApp number. Please send the order number you received.";
        UUID id = (UUID) o.get("id");
        if (o.get("whatsapp_confirmed_at") != null) return arabic ? "طلبك رقم " + o.get("order_number") + " متأكد بالفعل ✅ شكراً لك." : "Order " + o.get("order_number") + " is already confirmed ✅ Thank you.";
        if (!java.util.List.of("REQUESTED", "PREPARING").contains(o.get("status"))) return arabic ? "هذا الطلب لا يمكن تأكيده الآن." : "This order can no longer be confirmed.";

        StringBuilder items = new StringBuilder();
        for (var it : jdbc.sql("SELECT product_name_snapshot, variant_name_snapshot, quantity FROM commerce.order_items WHERE order_id = :o ORDER BY id").param("o", id).query().listOfRows())
            items.append("• ").append(it.get("product_name_snapshot")).append(it.get("variant_name_snapshot") == null ? "" : " (" + it.get("variant_name_snapshot") + ")").append(" × ").append(it.get("quantity")).append("\n");
        String address = String.valueOf(o.get("area") == null ? "" : o.get("area"));
        try { JsonNode a = JSON.readTree((String) o.get("addr")); address = (a.path("addressLine1").asText("") + " " + address).trim(); } catch (Exception ignored) { /* the area alone is enough */ }
        double total = ((Number) o.get("total_minor")).longValue() / 100.0;
        String tot = (total == Math.rint(total) ? String.valueOf((long) total) : String.format(java.util.Locale.ROOT, "%.2f", total)) + " " + ("EGP".equals(o.get("currency")) ? (arabic ? "جنيه" : "EGP") : o.get("currency"));
        String eta = o.get("eta_min_days") == null ? "" : o.get("eta_min_days") + "-" + o.get("eta_max_days");
        chat.savePending(chat.conversationId(tenantId, "WHATSAPP", sender, null), "{\"waConfirm\":\"" + id + "\"}");
        return arabic
                ? "أهلاً " + o.get("customer_name_snapshot") + " 👋\nده طلبك رقم " + o.get("order_number") + ":\n" + items + "الإجمالي: " + tot + "\nالعنوان: " + address + (eta.isEmpty() ? "" : "\nالتوصيل خلال " + eta + " أيام") + "\n\nلو كل شيء تمام اكتب \"تمام\" وهنأكد الطلب ✅"
                : "Hello " + o.get("customer_name_snapshot") + " 👋\nYour order " + o.get("order_number") + ":\n" + items + "Total: " + tot + "\nAddress: " + address + (eta.isEmpty() ? "" : "\nDelivery in " + eta + " days") + "\n\nIf everything is right, reply \"yes\" and we will confirm it ✅";
    }

    private String confirm(UUID tenantId, UUID conv, UUID orderId, String sender, boolean arabic) {
        var o = jdbc.sql("SELECT order_number, whatsapp_number, status FROM commerce.orders WHERE id = :o AND tenant_id = :t").param("o", orderId).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        jdbc.sql("UPDATE chat.conversations SET pending = NULL WHERE id = :c").param("c", conv).update();
        if (o == null || !samePhone((String) o.get("whatsapp_number"), sender) || !java.util.List.of("REQUESTED", "PREPARING").contains(o.get("status")))
            return arabic ? "تعذر تأكيد هذا الطلب." : "This order could not be confirmed.";
        jdbc.sql("UPDATE commerce.orders SET confirmed_at = coalesce(confirmed_at, now()), whatsapp_confirmed_at = now(), updated_at = now() WHERE id = :o AND tenant_id = :t").param("o", orderId).param("t", tenantId).update();
        audit.record(null, tenantId, "ORDER_CONFIRMED_WHATSAPP", "order", orderId, null);
        return arabic ? "تم تأكيد طلبك رقم " + o.get("order_number") + " ✅\nهنجهزه ونتواصل معاك للتسليم. شكراً لثقتك 🌸" : "Order " + o.get("order_number") + " is confirmed ✅\nWe will prepare it and contact you for delivery. Thank you!";
    }
}
