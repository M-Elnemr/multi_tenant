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
            notifications.notify(e.userId(), e.tenantId(), "ORDER_CREATED", ar ? "تم استلام طلبك" : "Order received",
                    ar ? "استلمنا طلبك رقم " + e.orderNumber() : "We received your order " + e.orderNumber(), json("orderId", e.orderId()), true);
            for (UUID staff : staffWith(e.tenantId(), "order.update_status"))
                notifications.notify(staff, e.tenantId(), "NEW_ORDER", ar ? "طلب جديد" : "New order", (ar ? "طلب جديد رقم " : "New order ") + e.orderNumber(), json("orderId", e.orderId()), false);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderStatus(OrderEvents.OrderStatusChanged e) {
        if (e.userId() == null) return;
        safely("OrderStatusChanged", () -> {
            boolean ar = ar(e.tenantId());
            notifications.notify(e.userId(), e.tenantId(), "ORDER_STATUS", ar ? "تحديث على طلبك" : "Order update",
                    (ar ? "حالة الطلب " : "Order ") + e.orderNumber() + ": " + e.newStatus(), json("orderId", e.orderId()), true);
        });
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
                for (UUID u : patientUsers(e.tenantId(), e.patientId()))
                    notifications.notify(u, e.tenantId(), "APPOINTMENT_CALLED", ar ? "حان دورك" : "It's your turn",
                            ar ? "الطبيب جاهز لاستقبالك الآن." : "The doctor is ready to see you now.", json("appointmentId", e.appointmentId()), false);
            } else if (Set_NEEDS_STAFF.contains(e.status())) {
                for (UUID staff : staffWith(e.tenantId(), "appointment.manage"))
                    notifications.notify(staff, e.tenantId(), "BOOKING_REQUEST", ar ? "طلب حجز جديد" : "New booking request", ar ? "لديك طلب حجز جديد" : "You have a new booking request", json("appointmentId", e.appointmentId()), false);
            } else if (Set_PATIENT_VISIBLE.contains(e.status())) {
                String title = ar ? "تحديث على موعدك" : "Appointment update";
                String body = ar ? "هناك تحديث على موعدك. افتح بوابة المريض للتفاصيل." : "There is an update on your appointment. Open your patient portal for details.";
                for (UUID u : patientUsers(e.tenantId(), e.patientId())) notifications.notify(u, e.tenantId(), "APPOINTMENT_" + e.status(), title, body, json("appointmentId", e.appointmentId()), true);
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPatientUpdate(MedicalEvents.PatientUpdate e) {
        safely("PatientUpdate", () -> {
            boolean ar = ar(e.tenantId());
            boolean added = "CLINIC_ADDED".equals(e.kind());
            for (UUID u : patientUsers(e.tenantId(), e.patientId()))
                notifications.notify(u, e.tenantId(), e.kind(), added ? (ar ? "تمت إضافتك إلى عيادة" : "A clinic added you") : (ar ? "تحديث جديد" : "New update"),
                        added ? (ar ? "أضافتك عيادة إلى قائمة مرضاها. افتح التطبيق لرؤيتها." : "A clinic added you to its patients. Open the app to see it.")
                              : (ar ? "لديك تحديث جديد في بوابة المريض." : "You have a new update in your patient portal."), null, true);
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
