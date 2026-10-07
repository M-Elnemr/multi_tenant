package com.platform.platform;

import com.platform.commerce.CatalogService;
import com.platform.commerce.StoreSettingsService;
import com.platform.core.onboarding.OnboardingService;
import com.platform.core.tenant.TenantRepository;
import com.platform.core.tenant.TenantType;
import com.platform.core.user.User;
import com.platform.core.user.UserRepository;
import com.platform.medical.PatientService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Development-only sample data (profile "dev"): a platform admin, a demo store with products and stock, and a demo clinic with a
 * patient. Entirely fictional - never real patient data (spec 61). Idempotent: skips anything that already exists.
 */
@Component
@ConditionalOnProperty(name = "app.dev-seed.enabled", havingValue = "true")
public class DevSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);
    private static final String PASSWORD = "Passw0rd!dev";

    private final OnboardingService onboarding;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JdbcClient jdbc;
    private final CatalogService catalog;
    private final StoreSettingsService storeSettings;
    private final PatientService patients;

    public DevSeed(OnboardingService onboarding, TenantRepository tenants, UserRepository users, PasswordEncoder encoder, JdbcClient jdbc,
                   CatalogService catalog, StoreSettingsService storeSettings, PatientService patients) {
        this.onboarding = onboarding;
        this.tenants = tenants;
        this.users = users;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.storeSettings = storeSettings;
        this.patients = patients;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.findByPhone("+201000000000").isEmpty()) {
            User admin = new User();
            admin.setPhone("+201000000000");
            admin.setFirstName("Platform");
            admin.setLastName("Admin");
            admin.setPasswordHash(encoder.encode(PASSWORD));
            admin.setStatus(User.Status.ACTIVE);
            admin = users.saveAndFlush(admin);
            jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", admin.getId()).update();
        }
        if (!tenants.existsBySlug("demo-store")) seedStore();
        if (!tenants.existsBySlug("demo-clinic")) seedClinic();
        log.info("""

                ==== DEV SEED ====
                Hosts (resolve to 127.0.0.1): demo-store.platform.localtest.me  demo-clinic.platform.localtest.me  platform.localtest.me
                Platform owner : 01000000000 / {}
                Store owner    : 01000000001 / {}
                Clinic doctor  : 01000000002 / {}
                ==================""", PASSWORD, PASSWORD, PASSWORD);
    }

    private void seedStore() {
        var r = onboarding.onboard(new OnboardingService.Command(TenantType.STORE, "Demo Store", "demo-store", "Mariam", "Store", "01000000001", null, PASSWORD, "en"), null, "127.0.0.1", "seed");
        UUID tenantId = r.tenant().getId();
        UUID owner = users.findByPhone("+201000000001").orElseThrow().getId();
        UUID branch = jdbc.sql("SELECT id FROM commerce.branches WHERE tenant_id = :t LIMIT 1").param("t", tenantId).query(UUID.class).single();
        Map<String, Object> cat = catalog.createCategory(tenantId, "Clothing", null, "Everyday wear", 1);
        UUID catId = (UUID) cat.get("id");
        catalog.createProduct(tenantId, owner, new CatalogService.ProductReq("Cotton T-Shirt", "Soft 100% cotton tee", "Soft cotton tee", catId, "Demo", "ACTIVE",
                List.of(new CatalogService.OptionReq("Size", List.of("S", "M", "L"))),
                List.of(variant("TEE-S", 19900, "S", branch, 25), variant("TEE-M", 19900, "M", branch, 40), variant("TEE-L", 19900, "L", branch, 15)), List.of()));
        catalog.createProduct(tenantId, owner, new CatalogService.ProductReq("Canvas Backpack", "Everyday backpack", null, catId, "Demo", "ACTIVE", List.of(),
                List.of(new CatalogService.VariantReq("BAG-1", 54900, 64900L, null, null, Map.of(), List.of(new CatalogService.StockReq(branch, 12)))), List.of()));
        storeSettings.setPaymentMethod(tenantId, owner, "CARD", true);
        storeSettings.createCoupon(tenantId, owner, new StoreSettingsService.CouponReq("WELCOME10", "PERCENT", 10, 0L, null, null, null, 1));
    }

    private static CatalogService.VariantReq variant(String sku, long price, String size, UUID branch, int qty) {
        return new CatalogService.VariantReq(sku, price, null, null, null, Map.of("Size", size), List.of(new CatalogService.StockReq(branch, qty)));
    }

    private void seedClinic() {
        var r = onboarding.onboard(new OnboardingService.Command(TenantType.CLINIC, "Demo Clinic", "demo-clinic", "Ahmed", "Hassan", "01000000002", null, PASSWORD, "en"), null, "127.0.0.1", "seed");
        UUID tenantId = r.tenant().getId();
        UUID doctorUser = users.findByPhone("+201000000002").orElseThrow().getId();
        var created = patients.create(tenantId, doctorUser, new PatientService.PatientReq("Sara", "Demo", "01000000003", null, null, "F", null, null, null, null, null, null, null));
        log.info("Demo patient {} portal activation PIN: {} (phone 01000000003)", created.get("patientCode"), created.get("activationPin"));
    }
}
