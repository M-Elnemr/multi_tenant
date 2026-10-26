package com.platform.chat;

import com.platform.commerce.GenderService;
import com.platform.core.auth.AccountAuthService;
import com.platform.open.OpenUrls;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Once a customer has CONFIRMED an order on WhatsApp (which proves the phone is theirs) they are told about their account, in the same chat: a new account with its
 * temporary password, or, when the phone already has one, that no new account was made and where to follow the order. Said once per order. An order that was only
 * requested (for example on Messenger) creates nothing until it is confirmed on WhatsApp.
 */
@Service
public class AccountNotice {
    private final JdbcClient jdbc;
    private final AccountAuthService accounts;
    private final OpenUrls urls;
    private final GenderService genders;
    private final String androidUrl;

    public AccountNotice(JdbcClient jdbc, AccountAuthService accounts, OpenUrls urls, GenderService genders,
                         @Value("${app.customer-app.android-url:https://play.google.com/store/apps/details?id=com.elmanassa.store}") String androidUrl) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.urls = urls;
        this.genders = genders;
        this.androidUrl = androidUrl == null ? "" : androidUrl.trim();
    }

    private static String localPhone(String e164) { return e164 != null && e164.startsWith("+20") ? "0" + e164.substring(3) : e164; }

    /** The id of the client account for this phone (E.164), or null: used to put the address the customer gave into their account. */
    public UUID clientIdForPhone(String phone) {
        return jdbc.sql("SELECT id FROM commerce.client_accounts WHERE phone = :p AND status = 'ACTIVE'").param("p", phone).query(UUID.class).optional().orElse(null);
    }

    /** The text to add to the confirmation message, or "" (already told, no phone, or the account is switched off). Contains {masculine|feminine} pairs for the caller to resolve. */
    public String forOrder(UUID tenantId, UUID orderId, boolean arabic) {
        var o = jdbc.sql("SELECT customer_phone_snapshot AS phone, customer_name_snapshot AS name, order_number, account_notified_at FROM commerce.orders WHERE id = :o AND tenant_id = :t").param("o", orderId).param("t", tenantId)
                .query().listOfRows().stream().findFirst().orElse(null);
        if (o == null || o.get("account_notified_at") != null || o.get("phone") == null || String.valueOf(o.get("phone")).isBlank()) return "";
        String phone = (String) o.get("phone");
        var ensured = accounts.ensureClientForConfirmedPhone(phone, (String) o.get("name"));
        jdbc.sql("UPDATE commerce.orders SET account_notified_at = now() WHERE id = :o").param("o", orderId).update();
        if (ensured.id() == null) return "";
        genders.forPhone(tenantId, phone);   // makes sure the gender guess is stored before the text is resolved
        String host = urls.hosts(List.of(tenantId)).get(tenantId);
        String web = host == null ? null : urls.baseUrl(host);
        String track = web == null ? null : web + "/track?n=" + o.get("order_number") + "&p=" + localPhone(phone);
        StringBuilder b = new StringBuilder();
        if (ensured.created()) {
            if (arabic) {
                b.append("🎉 عملنالك حساب عشان {تتابع|تتابعي} طلباتك في أي وقت:\n• رقم الدخول: ").append(localPhone(phone)).append("\n• كلمة المرور المؤقتة: ").append(ensured.tempPassword());
                if (track != null) b.append("\n\n📦 {تابع|تابعي} الأوردر ").append(o.get("order_number")).append(" من الموقع مباشرة:\n").append(track);
                if (web != null) b.append("\n\n🌐 دخول الحساب: ").append(web).append("/login");
                if (!androidUrl.isEmpty()) b.append("\n📱 أو من تطبيق الأندرويد (سجّل بنفس الرقم وافتح «طلباتي»): ").append(androidUrl);
                b.append("\n\n{غيّر|غيّري} كلمة المرور بعد أول دخول.");
            } else {
                b.append("🎉 We made an account so you can follow your orders any time:\n• Login: ").append(localPhone(phone)).append("\n• Temporary password: ").append(ensured.tempPassword());
                if (track != null) b.append("\n\n📦 Track order ").append(o.get("order_number")).append(" on the website:\n").append(track);
                if (web != null) b.append("\n\n🌐 Account sign-in: ").append(web).append("/login");
                if (!androidUrl.isEmpty()) b.append("\n📱 Or in the Android app (sign in with this number, open “My orders”): ").append(androidUrl);
                b.append("\n\nPlease change the password after your first login.");
            }
        } else {
            if (arabic) {
                b.append("✅ عندك حساب بالفعل بنفس الرقم، فمعملناش حساب جديد. {تابع|تابعي} طلبك من حسابك:");
                if (track != null) b.append("\n📦 ").append(track);
                if (web != null) b.append("\n🌐 ").append(web).append("/account");
                if (!androidUrl.isEmpty()) b.append("\n📱 تطبيق الأندرويد: ").append(androidUrl);
                b.append("\nلو {نسيت|نسيتي} كلمة المرور {اختار|اختاري} «نسيت كلمة المرور» في صفحة الدخول.");
            } else {
                b.append("✅ You already have an account with this number, so we did not create a new one. Follow your order from your account:");
                if (track != null) b.append("\n📦 ").append(track);
                if (web != null) b.append("\n🌐 ").append(web).append("/account");
                if (!androidUrl.isEmpty()) b.append("\n📱 Android app: ").append(androidUrl);
                b.append("\nIf you forgot the password, choose “Forgot password” on the sign-in page.");
            }
        }
        return b.toString();
    }
}
