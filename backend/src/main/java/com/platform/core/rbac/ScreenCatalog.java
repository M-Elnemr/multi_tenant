package com.platform.core.rbac;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The dashboard screens an owner can allow per employee (hidden / read only / read &amp; write) and the permissions behind each.
 * {@code read} permissions are what makes a screen visible; {@code write} are the extra ones that let the employee change data.
 * {@code alsoRead} are granted with read access only because the screen cannot work without them (they do not make it visible).
 * Staff management is deliberately not a screen: an employee can never be allowed to grant access.
 */
public final class ScreenCatalog {
    public record Screen(String key, List<String> read, List<String> write, List<String> alsoRead) {
        Screen(String key, List<String> read, List<String> write) { this(key, read, write, List.of()); }
    }

    private static final Screen REPORTS = new Screen("reports", List.of("report.read"), List.of());
    private static final Screen DOMAINS = new Screen("domains", List.of("domain.read"), List.of("domain.manage"));
    private static final Screen BILLING = new Screen("billing", List.of("billing.read"), List.of("billing.manage"));

    private static final Map<String, List<Screen>> BY_TYPE = Map.of(
            "STORE", List.of(
                    new Screen("orders", List.of("order.read"), List.of("order.update_status", "refund.create")),
                    new Screen("customers", List.of("customer.read"), List.of()),
                    new Screen("products", List.of("product.read"), List.of("product.create", "product.update", "product.delete")),
                    new Screen("inventory", List.of("inventory.read"), List.of("inventory.adjust", "branch.manage")),
                    new Screen("coupons", List.of("coupon.read"), List.of("coupon.manage")),
                    new Screen("reviews", List.of("review.read"), List.of("review.moderate")),
                    new Screen("ai", List.of("ai.read"), List.of("ai.manage")),
                    new Screen("integrations", List.of("integration.read"), List.of("integration.manage"), List.of("product.read", "order.read")),
                    new Screen("marketing", List.of("marketing.read"), List.of("marketing.manage"), List.of("product.read")),
                    new Screen("shipping", List.of("carrier.read"), List.of("carrier.manage"), List.of("order.read")),
                    REPORTS,
                    new Screen("settings", List.of("settings.read", "shipping.read"), List.of("settings.manage", "shipping.manage")),
                    DOMAINS, BILLING),
            "CLINIC", List.of(
                    new Screen("appointments", List.of("appointment.read"), List.of("appointment.manage")),
                    new Screen("patients", List.of("patient.read"), List.of("patient.create", "patient.update", "patient.export")),
                    new Screen("exam", List.of("medical_note.read", "prescription.read", "lab_order.read"),
                            List.of("medical_note.create", "prescription.create", "prescription.delete", "lab_order.create", "lab_result.upload", "lab_result.review"),
                            List.of("patient.read")),
                    new Screen("schedule", List.of("schedule.read"), List.of("schedule.manage")),
                    REPORTS,
                    new Screen("settings", List.of("settings.read"), List.of("settings.manage")),
                    DOMAINS, BILLING));

    private ScreenCatalog() {}

    public static List<Screen> screens(String tenantType) { return BY_TYPE.getOrDefault(tenantType, List.of()); }

    public static boolean isScreen(String tenantType, String key) { return screens(tenantType).stream().anyMatch(s -> s.key().equals(key)); }

    /** Permissions an employee gets for the given access map ({@code screen -> READ|WRITE}); unknown screens are ignored. */
    public static Set<String> permissions(String tenantType, Map<String, String> access) {
        Set<String> out = new TreeSet<>();
        for (Screen s : screens(tenantType)) {
            String level = access.get(s.key());
            if (level == null) continue;
            out.addAll(s.read());
            out.addAll(s.alsoRead());
            if ("WRITE".equals(level)) out.addAll(s.write());
        }
        return out;
    }

    /** The level a permission set gives on each visible screen: WRITE when it holds any write permission, READ when it holds any read one. */
    public static Map<String, String> levels(String tenantType, Set<String> perms) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Screen s : screens(tenantType)) {
            if (s.write().stream().anyMatch(perms::contains)) out.put(s.key(), "WRITE");
            else if (s.read().stream().anyMatch(perms::contains)) out.put(s.key(), "READ");
        }
        return out;
    }

    public static List<String> keys(String tenantType) {
        List<String> k = new ArrayList<>();
        screens(tenantType).forEach(s -> k.add(s.key()));
        return k;
    }
}
