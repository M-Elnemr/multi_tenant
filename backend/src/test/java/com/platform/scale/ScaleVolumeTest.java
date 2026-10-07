package com.platform.scale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.IntegrationTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * 1000 tenants (500 stores x 200 products / 100 orders, 500 clinics x 200 patients / 500 appointments) in ONE database: about 1.3 million rows.
 * Measures real request latency through the whole stack, checks the hot queries use tenant-leading indexes, and that tenants cannot see each other.
 * Run:  dropdb --if-exists platform_scale; createdb platform_scale && ./gradlew test -Pscale=true --tests '*ScaleVolumeTest'   (report in build/scale-report.md)
 */
@EnabledIfSystemProperty(named = "scale", matches = "true")
@TestPropertySource(properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/platform_scale?options=-c%20statement_timeout=30000")
class ScaleVolumeTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    final StringBuilder report = new StringBuilder();

    void sql(String s) { jdbc.sql(s).update(); }

    void seed() {
        long t0 = System.currentTimeMillis();
        sql("INSERT INTO core.users (phone, first_name, status) VALUES ('+201999999999', 'Volume', 'ACTIVE') ON CONFLICT DO NOTHING");
        sql("INSERT INTO core.tenants (slug, name, tenant_type, status) SELECT 'vs' || g, 'Volume Shop ' || g, 'STORE', 'ACTIVE' FROM generate_series(1, 500) g");
        sql("INSERT INTO core.tenants (slug, name, tenant_type, status) SELECT 'vc' || g, 'Volume Clinic ' || g, 'CLINIC', 'ACTIVE' FROM generate_series(1, 500) g");
        sql("INSERT INTO core.tenant_domains (tenant_id, host, kind, is_primary, is_verified) SELECT id, slug || '.platform.test', 'SUBDOMAIN', true, true FROM core.tenants WHERE slug ~ '^v[sc][0-9]+$'");
        sql("INSERT INTO billing.tenant_subscriptions (tenant_id, plan_id, status, current_period_end) SELECT t.id, (SELECT id FROM billing.subscription_plans WHERE code = CASE WHEN t.tenant_type = 'STORE' THEN 'STORE_PRO' ELSE 'CLINIC_PRO' END), 'TRIALING', now() + interval '10 days' FROM core.tenants t WHERE t.slug ~ '^v[sc][0-9]+$'");
        sql("INSERT INTO commerce.store_profiles (tenant_id, store_name) SELECT id, name FROM core.tenants WHERE slug ~ '^vs[0-9]+$'");
        sql("INSERT INTO commerce.branches (tenant_id, name, code) SELECT id, 'Main', 'MAIN' FROM core.tenants WHERE slug ~ '^vs[0-9]+$'");
        sql("INSERT INTO commerce.payment_method_settings (tenant_id, method, enabled) SELECT id, 'CASH_ON_DELIVERY', true FROM core.tenants WHERE slug ~ '^vs[0-9]+$'");
        sql("INSERT INTO commerce.shipping_methods (tenant_id, type, name, fee_minor) SELECT id, 'FIXED', 'Standard', 5000 FROM core.tenants WHERE slug ~ '^vs[0-9]+$'");
        sql("INSERT INTO medical.clinic_profiles (tenant_id, clinic_name) SELECT id, name FROM core.tenants WHERE slug ~ '^vc[0-9]+$'");
        sql("INSERT INTO medical.clinic_branches (tenant_id, name, code) SELECT id, 'Main', 'MAIN' FROM core.tenants WHERE slug ~ '^vc[0-9]+$'");
        sql("INSERT INTO medical.doctors (tenant_id, user_id, display_name) SELECT t.id, (SELECT id FROM core.users WHERE phone = '+201999999999'), 'Dr. Volume' FROM core.tenants t WHERE slug ~ '^vc[0-9]+$'");
        sql("INSERT INTO medical.appointment_services (tenant_id, name, duration_minutes) SELECT id, 'Consultation', 30 FROM core.tenants WHERE slug ~ '^vc[0-9]+$'");
        sql("INSERT INTO medical.doctor_schedules (tenant_id, doctor_id, branch_id, weekday, start_local_time, end_local_time) SELECT d.tenant_id, d.id, b.id, w, '10:00', '16:00' FROM medical.doctors d JOIN medical.clinic_branches b ON b.tenant_id = d.tenant_id, generate_series(1, 7) w WHERE d.display_name = 'Dr. Volume'");
        bulk();
        sql("ANALYZE");
        report.append("\nSeeded in ").append((System.currentTimeMillis() - t0) / 1000).append(" s\n");
    }

    /** Heavy data for every synthetic tenant AND for the two tenants created through the API (so the measured tenants are as big as all the others). */
    void bulk() {
        sql("DROP TABLE IF EXISTS public.scale_targets");
        sql("CREATE TABLE public.scale_targets AS SELECT id, tenant_type FROM core.tenants WHERE slug ~ '^v[sc][0-9]+$' OR slug LIKE 'big%'");
        sql("INSERT INTO commerce.products (tenant_id, name, slug, description, status) SELECT t.id, 'Product ' || g || ' ' || md5(random()::text), 'p-' || g, 'A reasonably long description text for the product ' || g, 'ACTIVE' FROM public.scale_targets t, generate_series(1, 200) g WHERE t.tenant_type = 'STORE'");
        sql("INSERT INTO commerce.product_variants (tenant_id, product_id, sku, price_minor, combo_key) SELECT p.tenant_id, p.id, 'SKU-' || p.id, (random() * 50000)::bigint + 100, '' FROM commerce.products p");
        sql("INSERT INTO commerce.inventory_items (tenant_id, branch_id, variant_id, quantity_on_hand) SELECT v.tenant_id, (SELECT b.id FROM commerce.branches b WHERE b.tenant_id = v.tenant_id LIMIT 1), v.id, 50 FROM commerce.product_variants v");
        sql("INSERT INTO commerce.orders (tenant_id, order_number, status, payment_status, payment_method, currency, subtotal_minor, total_minor, customer_name_snapshot, customer_phone_snapshot, created_at) "
                + "SELECT t.id, 'ST-' || g, (ARRAY['CONFIRMED','PROCESSING','DELIVERED','DELIVERED','CANCELLED'])[1 + g % 5], CASE WHEN g % 5 = 2 OR g % 5 = 3 THEN 'PAID' ELSE 'UNPAID' END, 'CASH_ON_DELIVERY', 'EGP', 10000 + g * 7, 15000 + g * 7, 'Customer ' || g, '+2010000' || g, now() - (random() * 90) * interval '1 day' FROM public.scale_targets t, generate_series(1, 100) g WHERE t.tenant_type = 'STORE'");
        sql("INSERT INTO commerce.order_items (order_id, tenant_id, sku_snapshot, product_name_snapshot, unit_price_minor, quantity, line_total_minor) SELECT o.id, o.tenant_id, 'SKU-X', 'Product ' || (o.id::text), 5000, g, 5000 * g FROM commerce.orders o, generate_series(1, 2) g");
        sql("INSERT INTO medical.patients (tenant_id, patient_code, first_name, last_name, phone) SELECT t.id, 'PAT-' || lpad(g::text, 6, '0'), 'Patient' || g, 'Family' || (g % 50), '+2011' || lpad((random() * 99999999)::int::text, 8, '0') FROM public.scale_targets t, generate_series(1, 200) g WHERE t.tenant_type = 'CLINIC'");
        sql("INSERT INTO medical.appointments (tenant_id, patient_id, doctor_id, branch_id, service_id, start_at, end_at, status, booking_source, price_minor) "
                + "SELECT t.id, (SELECT p.id FROM medical.patients p WHERE p.tenant_id = t.id AND p.patient_code = 'PAT-' || lpad(((g % 200) + 1)::text, 6, '0')), "
                + "(SELECT d.id FROM medical.doctors d WHERE d.tenant_id = t.id LIMIT 1), (SELECT b.id FROM medical.clinic_branches b WHERE b.tenant_id = t.id LIMIT 1), (SELECT s.id FROM medical.appointment_services s WHERE s.tenant_id = t.id LIMIT 1), "
                + "date_trunc('day', now()) - interval '60 days' + (g / 16) * interval '1 day' + (g % 16) * interval '30 minutes' + interval '8 hours', "
                + "date_trunc('day', now()) - interval '60 days' + (g / 16) * interval '1 day' + (g % 16) * interval '30 minutes' + interval '8 hours 30 minutes', "
                + "CASE WHEN g / 16 < 60 THEN 'COMPLETED' ELSE 'CONFIRMED' END, 'RECEPTION', 20000 FROM public.scale_targets t, generate_series(0, 499) g WHERE t.tenant_type = 'CLINIC'");
    }

    record Stat(String name, long p50, long p95, long max) {}

    Stat measure(String name, int n, Callable<Integer> call) throws Exception {
        for (int i = 0; i < 5; i++) call.call();   // warm up
        long[] ms = new long[n];
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            int code = call.call();
            ms[i] = (System.nanoTime() - s) / 1_000_000;
            assertThat(code).as(name).isBetween(200, 299);
        }
        java.util.Arrays.sort(ms);
        Stat st = new Stat(name, ms[n / 2], ms[(int) (n * 0.95)], ms[n - 1]);
        report.append(String.format("| %-46s | %5d | %5d | %5d |%n", st.name(), st.p50(), st.p95(), st.max()));
        return st;
    }

    int code(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getStatus(); }

    @Test
    void thousandTenantsStayFastIsolatedAndIndexed() throws Exception {
        Tenant bigStore = null, bigClinic = null;
        // two real tenants through the API (real auth, real tokens), created first so they get the same bulk data as everyone else
        bigStore = onboardNamed("STORE", "bigstore" + uniq());
        bigClinic = onboardNamed("CLINIC", "bigclinic" + uniq());
        seed();

        report.append("\n| request (p50 / p95 / max in ms)                 |   p50 |   p95 |   max |\n|---|---|---|---|\n");
        List<Stat> stats = new ArrayList<>();
        final Tenant store = bigStore, clinic = bigClinic;
        int[] i = {0};
        // public storefront traffic spread over 400 different stores (host decides the tenant)
        stats.add(measure("shop: product list (page 1) across 400 stores", 300, () -> code(onHost("vs" + (1 + (i[0]++ % 400)) + ".platform.test", null, "GET", "/api/v1/shop/products?pageSize=20", null))));
        stats.add(measure("shop: product search 'Product 1' across 400 stores", 300, () -> code(onHost("vs" + (1 + (i[0]++ % 400)) + ".platform.test", null, "GET", "/api/v1/shop/products?q=Product%201&pageSize=20", null))));
        stats.add(measure("shop: product page by slug across 400 stores", 300, () -> code(onHost("vs" + (1 + (i[0]++ % 400)) + ".platform.test", null, "GET", "/api/v1/shop/products/p-" + (1 + i[0] % 200), null))));
        stats.add(measure("shop: profile + payment/shipping across 400 stores", 300, () -> code(onHost("vs" + (1 + (i[0]++ % 400)) + ".platform.test", null, "GET", "/api/v1/shop/profile", null))));
        String[] ids = new String[100];
        for (int k = 0; k < 100; k++) {
            String prof = onHost("vc" + (k + 1) + ".platform.test", null, "GET", "/api/v1/clinic/public/profile", null).andReturn().getResponse().getContentAsString();
            ids[k] = JsonPath.read(prof, "$.doctors[0].id") + "," + JsonPath.read(prof, "$.branches[0].id") + "," + JsonPath.read(prof, "$.services[0].id");
        }
        stats.add(measure("clinic: public site data across 100 clinics", 200, () -> code(onHost("vc" + (1 + (i[0]++ % 100)) + ".platform.test", null, "GET", "/api/v1/clinic/public/profile", null))));
        stats.add(measure("clinic: free slots for a day across 100 clinics", 200, () -> {
            int k = i[0]++ % 100;
            String[] p = ids[k].split(",");
            return code(onHost("vc" + (k + 1) + ".platform.test", null, "GET", "/api/v1/clinic/public/slots?doctorId=%s&branchId=%s&serviceId=%s&date=%s".formatted(p[0], p[1], p[2], java.time.LocalDate.now().plusDays(3)), null));
        }));
        // dashboards of the two API-created tenants (each has the same data volume as every other tenant)
        stats.add(measure("store: orders list (page 1 of 100 orders)", 200, () -> code(onHost(store.host(), store.access(), "GET", "/api/v1/store/orders?pageSize=20", null))));
        stats.add(measure("store: dashboard summary + cash to collect", 200, () -> code(onHost(store.host(), store.access(), "GET", "/api/v1/store/reports/summary", null))));
        stats.add(measure("store: products list with stock", 200, () -> code(onHost(store.host(), store.access(), "GET", "/api/v1/store/products?q=Product%205&pageSize=20", null))));
        stats.add(measure("store: low-stock inventory", 200, () -> code(onHost(store.host(), store.access(), "GET", "/api/v1/store/inventory?lowStock=true&pageSize=20", null))));
        stats.add(measure("clinic: dashboard (today, month, unpaid)", 200, () -> code(onHost(clinic.host(), clinic.access(), "GET", "/api/v1/clinic/dashboard", null))));
        stats.add(measure("clinic: waiting-room queue", 200, () -> code(onHost(clinic.host(), clinic.access(), "GET", "/api/v1/clinic/queue", null))));
        stats.add(measure("clinic: patient search by name", 200, () -> code(onHost(clinic.host(), clinic.access(), "GET", "/api/v1/clinic/patients?q=Patient15&pageSize=20", null))));
        stats.add(measure("clinic: appointments for one day", 200, () -> code(onHost(clinic.host(), clinic.access(), "GET", "/api/v1/clinic/appointments?from=%s&to=%s&pageSize=50".formatted(java.time.LocalDate.now().minusDays(5), java.time.LocalDate.now().minusDays(5)), null))));

        // concurrent load: 16 clients hammering a mix of public and dashboard endpoints at once
        report.append("\nConcurrent load (16 clients, 4000 requests, mixed storefront / booking / dashboards):\n");
        int clients = 16, perClient = 250;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(clients);
        List<java.util.concurrent.Future<long[]>> futures = new ArrayList<>();
        long wall = System.nanoTime();
        for (int c = 0; c < clients; c++) {
            final int seedOffset = c * 37;
            futures.add(pool.submit(() -> {
                long[] lat = new long[perClient];
                for (int k = 0; k < perClient; k++) {
                    int n = seedOffset + k;
                    long st = System.nanoTime();
                    int rc = switch (k % 5) {
                        case 0 -> code(onHost("vs" + (1 + n % 400) + ".platform.test", null, "GET", "/api/v1/shop/products?pageSize=20", null));
                        case 1 -> code(onHost("vs" + (1 + n % 400) + ".platform.test", null, "GET", "/api/v1/shop/products/p-" + (1 + n % 200), null));
                        case 2 -> { String[] p = ids[n % 100].split(","); yield code(onHost("vc" + (1 + n % 100) + ".platform.test", null, "GET", "/api/v1/clinic/public/slots?doctorId=%s&branchId=%s&serviceId=%s&date=%s".formatted(p[0], p[1], p[2], java.time.LocalDate.now().plusDays(3)), null)); }
                        case 3 -> code(onHost(store.host(), store.access(), "GET", "/api/v1/store/orders?pageSize=20", null));
                        default -> code(onHost(clinic.host(), clinic.access(), "GET", "/api/v1/clinic/dashboard", null));
                    };
                    assertThat(rc).isBetween(200, 299);
                    lat[k] = (System.nanoTime() - st) / 1_000_000;
                }
                return lat;
            }));
        }
        List<Long> all = new ArrayList<>();
        for (var f : futures) for (long v : f.get()) all.add(v);
        pool.shutdown();
        double secs = (System.nanoTime() - wall) / 1e9;
        java.util.Collections.sort(all);
        long p95 = all.get((int) (all.size() * 0.95)), p99 = all.get((int) (all.size() * 0.99));
        report.append(String.format("%d requests in %.1f s = %.0f requests/second, p50 %d ms, p95 %d ms, p99 %d ms, max %d ms%n", all.size(), secs, all.size() / secs, all.get(all.size() / 2), p95, p99, all.get(all.size() - 1)));
        assertThat(p95).as("p95 under concurrent load").isLessThan(800);

        // every measured p95 must meet the spec target (spec 67: p95 < 800 ms)
        for (Stat s : stats) assertThat(s.p95()).as(s.name() + " p95").isLessThan(800);

        // the hot queries must use tenant-leading indexes, never scan the big tables
        report.append("\nQuery plans (no sequential scan on a large table):\n");
        UUID anyStore = jdbc.sql("SELECT id FROM core.tenants WHERE slug = 'vs77'").query(UUID.class).single();
        UUID anyClinic = jdbc.sql("SELECT id FROM core.tenants WHERE slug = 'vc77'").query(UUID.class).single();
        String[][] plans = {
            {"products by tenant+status", "SELECT * FROM commerce.products WHERE tenant_id = '%s' AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 20".formatted(anyStore)},
            {"product by slug", "SELECT * FROM commerce.products WHERE tenant_id = '%s' AND slug = 'p-50'".formatted(anyStore)},
            {"orders newest first", "SELECT * FROM commerce.orders WHERE tenant_id = '%s' ORDER BY created_at DESC LIMIT 20".formatted(anyStore)},
            {"orders by status", "SELECT count(*) FROM commerce.orders WHERE tenant_id = '%s' AND status = 'DELIVERED'".formatted(anyStore)},
            {"cash to collect", "SELECT count(*) FROM commerce.orders WHERE tenant_id = '%s' AND payment_method = 'CASH_ON_DELIVERY' AND payment_status = 'UNPAID'".formatted(anyStore)},
            {"inventory by tenant", "SELECT * FROM commerce.inventory_items WHERE tenant_id = '%s' AND quantity_on_hand <= 3".formatted(anyStore)},
            {"patient by code", "SELECT * FROM medical.patients WHERE tenant_id = '%s' AND patient_code = 'PAT-000042'".formatted(anyClinic)},
            {"patient by phone", "SELECT * FROM medical.patients WHERE tenant_id = '%s' AND phone = '+201100000000'".formatted(anyClinic)},
            {"appointments of a day", "SELECT * FROM medical.appointments WHERE tenant_id = '%s' AND start_at >= now() - interval '5 days' AND start_at < now() - interval '4 days' ORDER BY start_at".formatted(anyClinic)},
            {"appointments of a patient", "SELECT * FROM medical.appointments WHERE tenant_id = '%s' AND patient_id = (SELECT id FROM medical.patients WHERE tenant_id = '%s' LIMIT 1) ORDER BY start_at".formatted(anyClinic, anyClinic)},
        };
        for (String[] p : plans) {
            String plan = String.join("\n", jdbc.sql("EXPLAIN " + p[1]).query(String.class).list());
            boolean seq = plan.matches("(?s).*Seq Scan on (products|orders|patients|appointments|inventory_items|product_variants).*");
            report.append("- ").append(p[0]).append(": ").append(seq ? "SEQUENTIAL SCAN" : "indexed").append('\n');
            assertThat(seq).as("plan for " + p[0] + "\n" + plan).isFalse();
        }

        // isolation at volume: each tenant sees exactly its own 200 products / 100 orders; one tenant's order id is invisible to another
        for (String slug : new String[] {"vs1", "vs250", "vs500"}) {
            long n = jdbc.sql("SELECT count(*) FROM commerce.products WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", slug).query(Long.class).single();
            assertThat(n).isEqualTo(200);
            String body = onHost(slug + ".platform.test", null, "GET", "/api/v1/shop/products?pageSize=100", null).andReturn().getResponse().getContentAsString();
            assertThat((Integer) JsonPath.read(body, "$.meta.total")).isEqualTo(200);
        }
        UUID otherOrder = jdbc.sql("SELECT id FROM commerce.orders WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = 'vs9') LIMIT 1").query(UUID.class).single();
        onHost(store.host(), store.access(), "GET", "/api/v1/store/orders/" + otherOrder, null).andExpect(status().isNotFound());
        assertThat(((Number) jdbc.sql("SELECT count(*) FROM core.tenants").query(Long.class).single()).longValue()).isGreaterThanOrEqualTo(1002);

        long dbMb = jdbc.sql("SELECT pg_database_size(current_database()) / 1024 / 1024").query(Long.class).single();
        report.append("\nDatabase size with 1002 tenants: ").append(dbMb).append(" MB\n");
        for (String[] t : new String[][] {{"commerce.products"}, {"commerce.orders"}, {"medical.appointments"}, {"medical.patients"}})
            report.append(t[0]).append(": ").append(jdbc.sql("SELECT count(*) FROM " + t[0]).query(Long.class).single()).append(" rows\n");
        Files.writeString(Path.of("build/scale-report.md"), report.toString());
        System.out.println(report);
    }

    Tenant onboardNamed(String type, String slug) throws Exception {
        String phone = nextPhone();
        String res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/onboarding/tenants").header("Host", "platform.test")
                .contentType("application/json").content("{\"type\":\"%s\",\"name\":\"%s\",\"slug\":\"%s\",\"ownerFirstName\":\"Owner\",\"phone\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(type, slug, slug, phone)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Tenant(slug, JsonPath.read(res, "$.host"), phone, JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
    }
}
