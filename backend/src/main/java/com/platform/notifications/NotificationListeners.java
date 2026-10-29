package com.platform.notifications;

import com.platform.billing.SubscriptionEvents;
import com.platform.commerce.InventoryService;
import com.platform.commerce.OrderEvents;
import com.platform.medical.MedicalEvents;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns domain events into notifications after the originating transaction commits (spec 30). A notification
 * failure is logged and swallowed: it must never undo or delay the order/booking that triggered it.
 * Medical messages are deliberately generic - no clinical content in e-mails or lock-screen text (spec 56/90).
 */
@Component
public class NotificationListeners {
    private static final Logger log = LoggerFactory.getLogger(NotificationListeners.class);

    private final NotificationService notifications;
    private final JdbcClient jdbc;

    public NotificationListeners(NotificationService notifications, JdbcClient jdbc) {
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    private boolean ar(UUID tenantId) {
        return jdbc.sql("SELECT default_locale FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("ar").startsWith("ar");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderCreated(OrderEvents.OrderCreated e) {
        safely("OrderCreated", () -> {
            boolean ar = ar(e.tenantId());
            if (e.userId() != null)   // guests have no account to notify
                notifications.notify(e.userId(), e.tenantId(), "ORDER_CREATED", ar ? "تم استلام طلبك" : "Order received",
                        ar ? "استلمنا طلبك رقم " + e.orderNumber() : "We received your order " + e.orderNumber(), json("orderId", e.orderId()), true);
            String title = ar ? "طلب جديد" : "New order", body = (ar ? "طلب جديد رقم " : "New order ") + e.orderNumber();
            List<UUID> owners = staffWith(e.tenantId(), "order.update_status");
            for (UUID staff : owners) {
                notifications.notify(staff, e.tenantId(), "NEW_ORDER", title, body, json("orderId", e.orderId()), true);
                notifications.push(staff, e.tenantId(), title, body, "{\"type\":\"NEW_ORDER\",\"orderId\":\"" + e.orderId() + "\"}");   // the shop owner's phone rings through Firebase
            }
            if (!owners.isEmpty()) notifications.dispatchSoon();
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderStatus(OrderEvents.OrderStatusChanged e) {
        if (e.userId() == null) return;
        safely("OrderStatusChanged", () -> {
            boolean ar = ar(e.tenantId());
            notifications.notify(e.userId(), e.tenantId(), "ORDER_STATUS", ar ? "تحديث على طلبك" : "Order update",
                    (ar ? "حالة الطلب " : "Order ") + e.orderNumber() + ": " + statusLabel(e.newStatus(), ar), json("orderId", e.orderId()), true);
        });
    }

    /** The customer cancelled their own order: the people who handle orders are told, in the app and on the phone. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderCancelledByCustomer(OrderEvents.OrderCancelledByCustomer e) {
        safely("OrderCancelledByCustomer", () -> {
            boolean ar = ar(e.tenantId());
            String title = ar ? "العميل ألغى الطلب" : "Order cancelled by customer", body = (ar ? "العميل ألغى الطلب رقم " : "The customer cancelled order ") + e.orderNumber();
            String payload = "{\"type\":\"ORDER_CANCELLED\",\"orderId\":\"" + e.orderId() + "\"}";
            List<UUID> owners = staffWith(e.tenantId(), "order.update_status");
            for (UUID staff : owners) {
                notifications.notify(staff, e.tenantId(), "ORDER_CANCELLED", title, body, json("orderId", e.orderId()), true);
                notifications.push(staff, e.tenantId(), title, body, payload);
            }
            if (!owners.isEmpty()) notifications.dispatchSoon();
        });
    }

    /** The customer asked to cancel in the chat and said why: the people who handle orders get the reason, so they can call the customer and solve it. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderCancelRequested(OrderEvents.OrderCancelRequested e) {
        safely("OrderCancelRequested", () -> {
            boolean ar = ar(e.tenantId());
            String title = ar ? "طلب إلغاء أوردر" : "Cancellation request";
            String body = (ar ? "العميل عايز يلغي الطلب رقم " + e.orderNumber() + " — السبب: " : "The customer wants to cancel order " + e.orderNumber() + " — reason: ") + e.reason()
                    + (ar ? ". كلم العميل وشوف الحل." : ". Call the customer to sort it out.");
            String payload = "{\"type\":\"ORDER_CANCEL_REQUESTED\",\"orderId\":\"" + e.orderId() + "\"}";
            List<UUID> owners = staffWith(e.tenantId(), "order.update_status");
            for (UUID staff : owners) {
                notifications.notify(staff, e.tenantId(), "ORDER_CANCEL_REQUESTED", title, body, json("orderId", e.orderId()), true);
                notifications.push(staff, e.tenantId(), title, body, payload);
            }
            if (!owners.isEmpty()) notifications.dispatchSoon();
        });
    }

    /** A chat the assistant should not handle alone: the people who run the assistant are told, with the customer's words, and open the chat in the inbox. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onChatNeedsHuman(com.platform.chat.ChatNeedsHuman e) {
        safely("ChatNeedsHuman", () -> {
            boolean ar = ar(e.tenantId());
            String title = ar ? "عميل محتاج حد من المحل" : "A customer needs you";
            String said = e.customerText() == null ? "" : e.customerText().length() > 140 ? e.customerText().substring(0, 140) + "…" : e.customerText();
            String body = (ar ? e.reason() + " — العميل كتب: «" + said + "». افتح المحادثة ورد بنفسك." : e.reason() + " — the customer wrote: «" + said + "». Open the chat and reply yourself.");
            String payload = "{\"type\":\"CHAT_NEEDS_HUMAN\",\"conversationId\":\"" + e.conversationId() + "\"}";
            List<UUID> people = staffWith(e.tenantId(), "ai.manage");
            for (UUID staff : people) {
                notifications.notify(staff, e.tenantId(), "CHAT_NEEDS_HUMAN", title, body, json("conversationId", e.conversationId()), true);
                notifications.push(staff, e.tenantId(), title, body, payload);
            }
            if (!people.isEmpty()) notifications.dispatchSoon();
        });
    }

    /** A new shop or clinic signed up: every platform operator is told, in the app and on the phone (no tenant on the notification). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTenantCreated(com.platform.core.tenant.TenantCreatedEvent e) {
        safely("TenantCreated", () -> {
            String name = jdbc.sql("SELECT name FROM core.tenants WHERE id = :t").param("t", e.tenantId()).query(String.class).optional().orElse("");
            String title = e.type() == com.platform.core.tenant.TenantType.STORE ? "متجر جديد" : "عيادة جديدة";
            String payload = "{\"type\":\"TENANT_CREATED\",\"tenantId\":\"" + e.tenantId() + "\"}";
            List<UUID> admins = jdbc.sql("""
                    SELECT DISTINCT pr.user_id FROM core.user_platform_roles pr JOIN core.role_permissions rp ON rp.role_id = pr.role_id
                    JOIN core.permissions p ON p.id = rp.permission_id JOIN core.users u ON u.id = pr.user_id
                    WHERE p.code = 'platform.tenant.manage' AND u.status = 'ACTIVE'
                    """).query(UUID.class).list();
            for (UUID u : admins) {
                notifications.notify(u, null, "TENANT_CREATED", title, name, payload, false);
                notifications.push(u, null, title, name, payload);
            }
            if (!admins.isEmpty()) notifications.dispatchSoon();
        });
    }

    private static String statusLabel(String s, boolean ar) {
        return switch (s) {
            case "REQUESTED" -> ar ? "تم الطلب" : "Requested";
            case "PREPARING" -> ar ? "جاري التجهيز للشحن" : "Preparing to ship";
            case "SHIPPED" -> ar ? "تم الشحن" : "Shipped";
            case "ARRIVED" -> ar ? "وصل" : "Arrived";
            case "RETURNED" -> ar ? "مرتجع" : "Returned";
            case "CANCELLED" -> ar ? "ملغي" : "Cancelled";
            default -> s;
        };
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLowStock(InventoryService.LowStock e) {
        safely("LowStock", () -> {
            var v = jdbc.sql("SELECT v.sku, p.name FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id WHERE v.id = :v").param("v", e.variantId()).query().listOfRows().stream().findFirst().orElse(null);
            if (v == null) return;
            boolean ar = ar(e.tenantId());
            for (UUID staff : staffWith(e.tenantId(), "inventory.adjust"))
                notifications.notify(staff, e.tenantId(), "LOW_STOCK", ar ? "مخزون منخفض" : "Low stock", v.get("name") + " (" + v.get("sku") + "): " + e.available(), json("variantId", e.variantId()), false);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAppointment(MedicalEvents.AppointmentChanged e) {
        safely("AppointmentChanged", () -> {
            boolean ar = ar(e.tenantId());
            if ("CALLED".equals(e.status())) {
                String child = dependentName(e.tenantId(), e.patientId());   // a child's turn names the child, since it reaches the parent's phone
                for (UUID u : patientUsers(e.tenantId(), e.patientId())) {
                    String title = child != null ? (ar ? "حان دور " + child : child + "'s turn") : (ar ? "حان دورك" : "It's your turn");
                    String body = child != null ? (ar ? "الطبيب جاهز لاستقبال " + child + " الآن." : "The doctor is ready to see " + child + " now.") : (ar ? "الطبيب جاهز لاستقبالك الآن." : "The doctor is ready to see you now.");
                    notifications.notify(u, e.tenantId(), "APPOINTMENT_CALLED", title, body, json("appointmentId", e.appointmentId()), false);
                    notifications.push(u, e.tenantId(), title, body, "{\"type\":\"QUEUE_CALLED\",\"appointmentId\":\"" + e.appointmentId() + "\"}");   // reaches the patient's phone through Firebase
                }
                notifications.dispatchSoon();   // a "your turn" push must not wait for the next 30-second poll
            } else if ("IN_PROGRESS".equals(e.status()) || "NO_SHOW".equals(e.status()) || "QUEUE_LEFT".equals(e.status())) {
                queueMoved(e.tenantId(), e.appointmentId(), ar);
            } else if ("QUEUE_REORDERED".equals(e.status())) {
                queueReordered(e.tenantId(), e.appointmentId(), ar);
            } else if (Set_NEEDS_STAFF.contains(e.status())) {
                for (UUID staff : staffWith(e.tenantId(), "appointment.manage"))
                    notifications.notify(staff, e.tenantId(), "BOOKING_REQUEST", ar ? "طلب حجز جديد" : "New booking request", ar ? "لديك طلب حجز جديد" : "You have a new booking request", json("appointmentId", e.appointmentId()), false);
            } else if (Set_PATIENT_VISIBLE.contains(e.status())) {
                String child = dependentName(e.tenantId(), e.patientId());
                String title = child != null ? (ar ? "تحديث على موعد " + child : "Appointment update for " + child) : (ar ? "تحديث على موعدك" : "Appointment update");
                String body = ar ? "هناك تحديث على الموعد. افتح بوابة المريض للتفاصيل." : "There is an update on the appointment. Open your patient portal for details.";
                for (UUID u : patientUsers(e.tenantId(), e.patientId())) notifications.notify(u, e.tenantId(), "APPOINTMENT_" + e.status(), title, body, json("appointmentId", e.appointmentId()), true);
            }
        });
    }

    /**
     * Someone ahead left the waiting line (started their visit, or did not show up): everybody behind them in the same doctor's queue that day
     * is told how many patients are still before them. Each change is announced once (see medical.queue_push_state).
     */
    private void queueMoved(UUID tenantId, UUID leftAppointmentId, boolean ar) {
        String tz = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("Africa/Cairo");
        var left = jdbc.sql("SELECT doctor_id, queue_number, checked_in_at FROM medical.appointments WHERE id = :a AND tenant_id = :t").param("a", leftAppointmentId).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        if (left == null || left.get("queue_number") == null || left.get("checked_in_at") == null) return;
        var behind = jdbc.sql("""
                SELECT b.id, b.patient_id,
                  (SELECT count(*) FROM medical.appointments c WHERE c.tenant_id = b.tenant_id AND c.doctor_id = b.doctor_id AND c.status = 'CHECKED_IN'
                     AND (c.checked_in_at AT TIME ZONE :tz)::date = (b.checked_in_at AT TIME ZONE :tz)::date AND c.queue_number < b.queue_number) AS ahead
                FROM medical.appointments b
                WHERE b.tenant_id = :t AND b.doctor_id = :d AND b.status = 'CHECKED_IN' AND b.queue_number > :n
                  AND (b.checked_in_at AT TIME ZONE :tz)::date = (CAST(:ci AS timestamptz) AT TIME ZONE :tz)::date
                """).param("t", tenantId).param("d", left.get("doctor_id")).param("n", left.get("queue_number")).param("tz", tz).param("ci", left.get("checked_in_at")).query().listOfRows();
        announceAhead(tenantId, behind, ar);
    }

    /** The line was rearranged: everyone who moved closer to the front is told once (those pushed back hear nothing). */
    private void queueReordered(UUID tenantId, UUID anyAppointmentId, boolean ar) {
        String tz = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("Africa/Cairo");
        var any = jdbc.sql("SELECT doctor_id, checked_in_at FROM medical.appointments WHERE id = :a AND tenant_id = :t").param("a", anyAppointmentId).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        if (any == null || any.get("checked_in_at") == null) return;
        var waiting = jdbc.sql("""
                SELECT b.id, b.patient_id,
                  (SELECT count(*) FROM medical.appointments c WHERE c.tenant_id = b.tenant_id AND c.doctor_id = b.doctor_id AND c.status = 'CHECKED_IN'
                     AND (c.checked_in_at AT TIME ZONE :tz)::date = (b.checked_in_at AT TIME ZONE :tz)::date AND c.queue_number < b.queue_number) AS ahead
                FROM medical.appointments b
                WHERE b.tenant_id = :t AND b.doctor_id = :d AND b.status = 'CHECKED_IN'
                  AND (b.checked_in_at AT TIME ZONE :tz)::date = (CAST(:ci AS timestamptz) AT TIME ZONE :tz)::date
                """).param("t", tenantId).param("d", any.get("doctor_id")).param("tz", tz).param("ci", any.get("checked_in_at")).query().listOfRows();
        announceAhead(tenantId, waiting, ar);
    }

    private void announceAhead(UUID tenantId, java.util.List<java.util.Map<String, Object>> behind, boolean ar) {
        boolean any = false;
        for (var b : behind) {
            UUID appt = (UUID) b.get("id");
            int ahead = ((Number) b.get("ahead")).intValue();
            Integer last = jdbc.sql("SELECT last_ahead FROM medical.queue_push_state WHERE appointment_id = :a").param("a", appt).query(Integer.class).optional().orElse(null);
            if (last != null && ahead > last) { jdbc.sql("UPDATE medical.queue_push_state SET last_ahead = :n, updated_at = now() WHERE appointment_id = :a").param("a", appt).param("n", ahead).update(); continue; }   // pushed back: remember it silently
            if (last != null && ahead >= last) continue;   // nothing new to say
            jdbc.sql("""
                    INSERT INTO medical.queue_push_state (appointment_id, last_ahead) VALUES (:a, :n)
                    ON CONFLICT (appointment_id) DO UPDATE SET last_ahead = :n, updated_at = now()
                    """).param("a", appt).param("n", ahead).update();
            UUID patientId = (UUID) b.get("patient_id");
            String child = dependentName(tenantId, patientId);
            String title = ar ? "تقدّمت في الدور" : "You moved up in the queue";
            String body = ahead == 0
                    ? (child != null ? (ar ? child + " هو التالي" : child + " is next") : (ar ? "أنت التالي" : "You are next"))
                    : (child != null ? (ar ? "قبل " + child + " " + ahead + " مرضى" : ahead + " patient(s) before " + child) : (ar ? ahead + " مرضى قبلك" : ahead + " patient(s) before you"));
            String payload = "{\"type\":\"QUEUE_POSITION\",\"appointmentId\":\"" + appt + "\",\"aheadOfYou\":\"" + ahead + "\"}";
            for (UUID u : patientUsers(tenantId, patientId)) { notifications.push(u, tenantId, title, body, payload); any = true; }
        }
        if (any) notifications.dispatchSoon();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPatientUpdate(MedicalEvents.PatientUpdate e) {
        safely("PatientUpdate", () -> {
            boolean ar = ar(e.tenantId());
            String title, body;
            switch (e.kind()) {
                case "CLINIC_ADDED" -> { title = ar ? "تمت إضافتك إلى عيادة" : "A clinic added you"; body = ar ? "أضافتك عيادة إلى قائمة مرضاها. افتح التطبيق لرؤيتها." : "A clinic added you to its patients. Open the app to see it."; }
                case "LAB_ORDERED" -> { title = ar ? "طلب جديد من الطبيب" : "New request from your doctor"; body = ar ? "طلب لك الطبيب تحليلًا أو أشعة. افتح التطبيق لرفع النتيجة." : "Your doctor requested a test. Open the app to upload the result."; }
                case "LAB_REVIEWED" -> { title = ar ? "الطبيب راجع نتيجتك" : "Your doctor reviewed your result"; body = ar ? "افتح ملفك الطبي لمشاهدة التفاصيل." : "Open your medical file to see the details."; }
                default -> { title = ar ? "تحديث جديد" : "New update"; body = ar ? "لديك تحديث جديد في بوابة المريض." : "You have a new update in your patient portal."; }
            }
            boolean phone = !"CLINIC_ADDED".equals(e.kind());
            for (UUID u : patientUsers(e.tenantId(), e.patientId())) {
                notifications.notify(u, e.tenantId(), e.kind(), title, body, null, true);
                if (phone) notifications.push(u, e.tenantId(), title, body, "{\"type\":\"PATIENT_UPDATE\",\"kind\":\"" + e.kind() + "\"}");
            }
            if (phone) notifications.dispatchSoon();
        });
    }

    /** A patient uploaded a result (or ticked a test as done): everyone who reviews results is told, in the app and on the phone. No clinical content in the text. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLabFromPatient(MedicalEvents.LabResultFromPatient e) {
        safely("LabResultFromPatient", () -> {
            boolean ar = ar(e.tenantId());
            String title = e.markedDone() ? (ar ? "مريض أنهى تحليلًا مطلوبًا" : "A patient finished a requested test") : (ar ? "نتيجة تحليل جديدة من مريض" : "New test result from a patient");
            String body = ar ? "افتح ملف المريض لمراجعة النتيجة." : "Open the patient's file to review the result.";
            String payload = "{\"type\":\"LAB_RESULT_FROM_PATIENT\",\"labOrderId\":\"" + e.labOrderId() + "\",\"patientId\":\"" + e.patientId() + "\"}";
            for (UUID staff : staffWith(e.tenantId(), "lab_result.review")) {
                notifications.notify(staff, e.tenantId(), "LAB_RESULT_FROM_PATIENT", title, body, payload, false);
                notifications.push(staff, e.tenantId(), title, body, payload);
            }
            notifications.dispatchSoon();
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscription(SubscriptionEvents.StateChanged e) {
        safely("SubscriptionStateChanged", () -> {
            boolean ar = ar(e.tenantId());
            String title = "SUSPENDED".equals(e.status()) ? (ar ? "تم إيقاف حسابك" : "Your account was suspended") : (ar ? "اشتراكك يحتاج إلى سداد" : "Your subscription needs payment");
            String body = ar ? "يرجى تجديد الاشتراك من صفحة الفواتير للحفاظ على عملك." : "Please renew your subscription from the billing page to keep your site running.";
            for (UUID u : staffWith(e.tenantId(), "billing.manage")) notifications.notify(u, e.tenantId(), "SUBSCRIPTION_" + e.status(), title, body, null, true);
        });
    }

    /** appointment_reminder job (spec 31): confirmed appointments starting within 24h get one generic reminder. */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT3M")
    @Transactional
    public void appointmentReminders() {
        List<Map<String, Object>> due = jdbc.sql("""
                UPDATE medical.appointments SET reminder_sent_at = now()
                WHERE status = 'CONFIRMED' AND reminder_sent_at IS NULL AND start_at > now() AND start_at < now() + interval '24 hours'
                  AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = medical.appointments.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED'))
                RETURNING id, tenant_id, patient_id
                """).query().listOfRows();
        for (var a : due) {
            UUID tenantId = (UUID) a.get("tenant_id");
            safely("Reminder", () -> {
                boolean ar = ar(tenantId);
                for (UUID u : patientUsers(tenantId, (UUID) a.get("patient_id")))
                    notifications.notify(u, tenantId, "APPOINTMENT_REMINDER", ar ? "تذكير بموعدك" : "Appointment reminder",
                            ar ? "لديك موعد خلال 24 ساعة. افتح بوابة المريض للتفاصيل." : "You have an appointment within 24 hours. Open your patient portal for details.", json("appointmentId", a.get("id")), true);
            });
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private static final java.util.Set<String> Set_NEEDS_STAFF = java.util.Set.of("REQUESTED", "PENDING_CONFIRMATION");
    private static final java.util.Set<String> Set_PATIENT_VISIBLE = java.util.Set.of("CONFIRMED", "CANCELLED", "REJECTED");

    List<UUID> staffWith(UUID tenantId, String permission) {
        return jdbc.sql("""
                SELECT DISTINCT m.user_id FROM core.user_tenant_memberships m JOIN core.membership_roles mr ON mr.membership_id = m.id
                JOIN core.role_permissions rp ON rp.role_id = mr.role_id JOIN core.permissions p ON p.id = rp.permission_id
                WHERE m.tenant_id = :t AND m.status = 'ACTIVE' AND p.code = :p
                """).param("t", tenantId).param("p", permission).query(UUID.class).list();
    }

    /** First name when the patient is a dependent (a child managed by a parent), otherwise null. */
    String dependentName(UUID tenantId, UUID patientId) {
        return jdbc.sql("SELECT first_name FROM medical.patients WHERE id = :p AND tenant_id = :t AND guardian_patient_id IS NOT NULL")
                .param("p", patientId).param("t", tenantId).query(String.class).optional().orElse(null);
    }

    List<UUID> patientUsers(UUID tenantId, UUID patientId) {
        return jdbc.sql("""
                SELECT user_id FROM medical.patients WHERE id = :p AND tenant_id = :t AND user_id IS NOT NULL
                UNION SELECT guardian_user_id FROM medical.patient_guardians WHERE patient_id = :p AND tenant_id = :t
                """).param("p", patientId).param("t", tenantId).query(UUID.class).list();
    }

    private static String json(String k, Object v) { return v == null ? null : "{\"" + k + "\":\"" + v + "\"}"; }

    private void safely(String what, Runnable r) {
        try { r.run(); } catch (Exception ex) { log.warn("Notification for {} failed: {}", what, ex.getMessage()); }
    }
}
