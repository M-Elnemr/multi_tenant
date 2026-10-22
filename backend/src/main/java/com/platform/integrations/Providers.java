package com.platform.integrations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the UI needs to list a platform before connecting: which countries it serves, what the owner must type (or whether it is a login),
 * and what the platform can do. The country filter on the connect screens reads {@code countries}.
 */
public final class Providers {
    private Providers() {}

    public static final String MARKETPLACE = "MARKETPLACE", STORE_PLATFORM = "STORE_PLATFORM", CARRIER = "CARRIER", ADS = "ADS";

    /** {@code labelAr/labelEn}: form label; secret fields are masked and never returned. */
    public record Field(String key, String labelEn, String labelAr, boolean secret, String placeholder, boolean multiline) {
        static Field text(String key, String en, String ar, String ph) { return new Field(key, en, ar, false, ph, false); }
        static Field secret(String key, String en, String ar) { return new Field(key, en, ar, true, "", false); }
        static Field key(String key, String en, String ar) { return new Field(key, en, ar, true, "", true); }
    }

    /**
     * @param status READY = implemented; BETA = implemented against the platform's documentation but its access is gated or not verified yet; SOON = listed, not built
     * @param oauth the owner logs in on the platform instead of typing keys (needs the platform app's keys on the server)
     * @param can what the adapter supports: PRODUCTS, PUSH, CREATE, ORDERS, ACTIONS, TRACKING, LABEL
     */
    public record Def(String id, String name, String kind, List<String> countries, List<Field> fields, boolean oauth, String status, List<String> can, String helpEn, String helpAr, String docsUrl) {}

    private static final List<String> ALL = List.of("EG", "SA", "AE");

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    private static void add(Def d) { DEFS.put(d.id(), d); }

    static {
        add(new Def("AMAZON", "Amazon", MARKETPLACE, ALL,
                List.of(Field.text("sellerId", "Seller ID", "رقم البائع (Seller ID)", "A1B2C3D4E5"), Field.secret("refreshToken", "Refresh token", "رمز التحديث (Refresh token)"),
                        Field.text("lwaClientId", "LWA client ID (your SP-API app)", "معرّف تطبيق SP-API (LWA client id)", "amzn1.application-oa2-client..."), Field.secret("lwaClientSecret", "LWA client secret", "السر الخاص بالتطبيق (LWA client secret)")),
                true, "READY", List.of("PRODUCTS", "PUSH", "CREATE", "ORDERS", "ACTIONS"),
                "Log in with Amazon Seller Central for your country, or paste the credentials of your own SP-API app (Seller Central > Apps and Services > Develop Apps).",
                "سجّل الدخول من Seller Central لبلدك، أو ألصق بيانات تطبيق SP-API الخاص بك (Seller Central ← التطبيقات والخدمات ← تطوير التطبيقات).", "https://developer-docs.amazon.com/sp-api"));
        add(new Def("NOON", "noon", MARKETPLACE, ALL,
                List.of(Field.text("keyId", "Key ID", "معرّف المفتاح (key_id)", ""), Field.key("privateKey", "Private key (from the .json key file)", "المفتاح الخاص (private_key من ملف الـ json)"), Field.text("projectCode", "Project code", "كود المشروع (project_code)", "PRJ12345"),
                        Field.text("warehouseCode", "Warehouse code (from Seller Lab)", "كود المخزن (من Seller Lab)", "WH-CAI-01")),
                false, "BETA", List.of("PUSH", "ORDERS"),
                "Create an API user (service account) at access.noon.partners > API access, download its .json key file and copy key_id, private_key and project_code here.",
                "أنشئ مستخدم API (service account) من access.noon.partners ← API access، نزّل ملف المفتاح json وانسخ key_id و private_key و project_code هنا.", "https://noon-docs.noonpartners.dev"));
        add(new Def("SHOPIFY", "Shopify", STORE_PLATFORM, ALL,
                List.of(Field.text("shopDomain", "Store address", "عنوان المتجر", "my-store.myshopify.com"), Field.secret("accessToken", "Admin API access token", "رمز الوصول (Admin API access token)")),
                false, "READY", List.of("PRODUCTS", "PUSH", "CREATE", "ORDERS", "ACTIONS"),
                "In Shopify admin: Settings > Apps > Develop apps > create an app, allow read/write products, inventory and orders, install it and copy the Admin API access token.",
                "من لوحة شوبيفاي: الإعدادات ← التطبيقات ← تطوير التطبيقات ← أنشئ تطبيقاً، فعّل صلاحيات المنتجات والمخزون والطلبات (قراءة وكتابة)، ثبّته وانسخ رمز الوصول.", "https://shopify.dev/docs/api/admin-rest"));
        add(new Def("WOOCOMMERCE", "WooCommerce", STORE_PLATFORM, ALL,
                List.of(Field.text("siteUrl", "Site address", "عنوان الموقع", "https://my-store.com"), Field.text("consumerKey", "Consumer key", "Consumer key", "ck_..."), Field.secret("consumerSecret", "Consumer secret", "Consumer secret")),
                false, "READY", List.of("PRODUCTS", "PUSH", "CREATE", "ORDERS", "ACTIONS"),
                "In WordPress: WooCommerce > Settings > Advanced > REST API > Add key with Read/Write access, then paste the consumer key and secret.",
                "من ووردبريس: WooCommerce ← الإعدادات ← متقدم ← REST API ← إضافة مفتاح بصلاحية قراءة/كتابة، ثم الصق المفتاح والسر.", "https://woocommerce.github.io/woocommerce-rest-api-docs"));
        add(new Def("SALLA", "Salla", STORE_PLATFORM, List.of("SA", "AE"),
                List.of(Field.secret("accessToken", "Access token", "رمز الوصول (Access token)"), Field.secret("refreshToken", "Refresh token (optional)", "رمز التحديث (اختياري)")),
                true, "READY", List.of("PRODUCTS", "PUSH", "CREATE", "ORDERS", "ACTIONS"),
                "Log in with Salla to authorize elmanassa, or paste an access token from your Salla Partners app.",
                "سجّل الدخول بسلة لمنح الصلاحية، أو الصق رمز وصول من تطبيقك في بوابة شركاء سلة.", "https://docs.salla.dev"));
        add(new Def("YOUCAN", "YouCan", STORE_PLATFORM, ALL,
                List.of(Field.secret("accessToken", "Access token", "رمز الوصول (Access token)"), Field.secret("refreshToken", "Refresh token (optional)", "رمز التحديث (اختياري)")),
                true, "BETA", List.of("PRODUCTS", "PUSH", "CREATE", "ORDERS"),
                "Log in with YouCan to authorize elmanassa, or paste an access token from your YouCan Partner app.",
                "سجّل الدخول بيوكان لمنح الصلاحية، أو الصق رمز وصول من تطبيقك في حساب الشركاء.", "https://developer.youcan.shop"));

        add(new Def("ZID", "Zid", STORE_PLATFORM, List.of("SA", "AE"),
                List.of(Field.secret("authorization", "Authorization token", "رمز التفويض (Authorization)"), Field.secret("managerToken", "Manager token (X-Manager-Token)", "رمز المدير (X-Manager-Token)"), Field.text("storeId", "Store ID (optional)", "معرّف المتجر (اختياري)", "")),
                false, "BETA", List.of("PRODUCTS", "PUSH", "CREATE", "ORDERS", "ACTIONS"),
                "Create an app in the Zid Partner dashboard, authorize it on your store and paste the two tokens the authorization returns.",
                "أنشئ تطبيقاً في لوحة شركاء زد، فعّله على متجرك والصق الرمزين اللذين تعطيهما عملية التفويض.", "https://docs.zid.sa"));

        add(new Def("ARAMEX", "Aramex", CARRIER, ALL,
                List.of(Field.text("userName", "Username (email)", "اسم المستخدم (البريد)", ""), Field.secret("password", "Password", "كلمة المرور"), Field.text("accountNumber", "Account number", "رقم الحساب", ""),
                        Field.text("accountPin", "Account PIN", "رقم PIN للحساب", ""), Field.text("accountEntity", "Account entity", "كيان الحساب (مثل CAI / RUH / DXB)", "CAI"), Field.text("accountCountryCode", "Account country code", "رمز دولة الحساب", "EG"),
                        Field.text("sandbox", "Use Aramex test servers - type yes (optional)", "استخدام خوادم التجربة - اكتب yes (اختياري)", "")),
                false, "BETA", List.of("CREATE", "TRACKING", "LABEL"),
                "Ask your Aramex account manager for the Shipping API credentials (they give separate sandbox and production sets).",
                "اطلب من مدير حسابك في أرامكس بيانات Shipping API (تعطيك بيانات منفصلة للتجربة والتشغيل).", "https://www.aramex.com/developers"));
        add(new Def("BOSTA", "Bosta", CARRIER, List.of("EG"),
                List.of(Field.secret("apiKey", "API key", "مفتاح API")),
                false, "BETA", List.of("CREATE", "TRACKING"),
                "In the Bosta dashboard: Settings > Integrations > API key.", "من لوحة بوسطة: الإعدادات ← التكاملات ← API key.", "https://docs.bosta.co"));
        add(new Def("MYLERZ", "Mylerz", CARRIER, List.of("EG"),
                List.of(Field.text("username", "Username", "اسم المستخدم", ""), Field.secret("password", "Password", "كلمة المرور"), Field.text("warehouseName", "Pickup warehouse name", "اسم مخزن الاستلام", "")),
                false, "BETA", List.of("CREATE"),
                "Use the credentials Mylerz gave you for API access.", "استخدم بيانات الدخول التي أعطتك إياها مايلرز للربط البرمجي.", ""));
        add(new Def("AYMAKAN", "Aymakan", CARRIER, List.of("SA", "AE"),
                List.of(Field.secret("apiKey", "API key (security code)", "مفتاح API (الكود الأمني)"), Field.text("sandbox", "Use Aymakan test servers - type yes (optional)", "استخدام خوادم التجربة - اكتب yes (اختياري)", "")),
                false, "BETA", List.of("CREATE", "TRACKING", "LABEL"),
                "In your Aymakan account: Technical > AyMakan API, copy the security code.", "من حسابك في أيمكان: Technical ← AyMakan API، انسخ الكود الأمني.", "https://developer.aymakan.com.sa"));
        for (String[] c : new String[][] {{"NAQEL", "Naqel", "SA,AE"}, {"SMSA", "SMSA Express", "SA"}, {"JT", "J&T Express", "EG,SA,AE"}, {"SPL", "Saudi Post (SPL)", "SA"}, {"EMIRATES_POST", "Emirates Post", "AE"},
                {"TURBO", "Turbo", "EG"}, {"SHIPA", "Shipa Delivery", "AE"}, {"QUIQUP", "Quiqup", "AE"}, {"ZAJIL", "Zajil", "SA"}, {"SALASA", "Salasa", "SA"}, {"THABIT", "Thabit Logistics", "SA"}, {"OTO", "OTO (multi-carrier)", "SA,AE"}, {"FETCHR", "Fetchr", "SA,AE"}})
            add(new Def(c[0], c[1], CARRIER, List.of(c[2].split(",")), List.of(), false, "SOON", List.of(), "Coming soon.", "قريباً.", ""));

        add(new Def("META_ADS", "Meta Ads", ADS, ALL, List.of(), true, "READY", List.of("CAMPAIGNS"),
                "Connect with Facebook and choose the ad account.", "اربط حسابك على فيسبوك واختر الحساب الإعلاني.", "https://developers.facebook.com/docs/marketing-apis"));
    }

    public static Def get(String id) { return DEFS.get(id == null ? "" : id.toUpperCase()); }

    public static java.util.Collection<Def> all() { return DEFS.values(); }

    public static boolean supports(Def d, String capability) { return d.can().contains(capability); }

    /** ISO country -> currency used when the platform does not say. */
    public static String currencyOf(String country) { return "SA".equals(country) ? "SAR" : "AE".equals(country) ? "AED" : "EGP"; }
}
