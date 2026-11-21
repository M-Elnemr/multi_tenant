package com.platform.affiliate;

import com.platform.core.auth.MarketerAuthFilter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** The marketer's own API (platform site). Sign-in is a marketer-only token: the web layer keeps it in an httpOnly cookie and sends it as X-Marketer-Token. */
@RestController
@RequestMapping("/api/v1/marketer")
public class MarketerController {
    public record RegisterReq(String name, String phone, String email, String password) {}
    public record LoginReq(String phone, String password) {}
    public record ProfileReq(String name, String email, String bio, String city, String channelsJson, String payoutNote) {}
    public record PasswordReq(String current, String next) {}
    public record ApplyReq(String message) {}
    public record NoteReq(String note) {}
    public record RateReq(Integer rating, String comment) {}
    public record TextReq(String body) {}
    public record DisputeReq(UUID commissionId, String reason) {}

    private final MarketerAccounts accounts;
    private final MarketerService svc;

    public MarketerController(MarketerAccounts accounts, MarketerService svc) { this.accounts = accounts; this.svc = svc; }

    private static UUID me() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return ((MarketerAuthFilter.Marketer) a.getPrincipal()).id();
    }

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody RegisterReq r, @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        var s = accounts.register(r.name(), r.phone(), r.email(), r.password(), ip == null ? null : ip.split(",")[0].trim());
        return Map.of("token", s.token(), "account", s.account());
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq r, @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        var s = accounts.login(r.phone(), r.password(), ip == null ? null : ip.split(",")[0].trim());
        return Map.of("token", s.token(), "account", s.account());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Marketer-Token", required = false) String token) { accounts.logout(token); return Map.of("ok", true); }

    @GetMapping("/me") public Map<String, Object> profile() { return accounts.account(me()); }
    @PutMapping("/me") public Map<String, Object> update(@RequestBody ProfileReq r) { return accounts.update(me(), r.name(), r.email(), r.bio(), r.city(), r.channelsJson(), r.payoutNote()); }
    @PostMapping("/password") public Map<String, Object> password(@RequestBody PasswordReq r) { accounts.changePassword(me(), r.current(), r.next()); return Map.of("ok", true); }

    @GetMapping("/marketplace")
    public Map<String, Object> marketplace(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return svc.marketplace(me(), q, page, size); }

    @PostMapping("/shops/{tenantId}/apply") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> apply(@PathVariable UUID tenantId, @RequestBody(required = false) ApplyReq r) { return svc.apply(me(), tenantId, r == null ? null : r.message()); }

    @GetMapping("/shops") public List<Map<String, Object>> shops() { return svc.myShops(me()); }
    @GetMapping("/stats") public Map<String, Object> stats() { return svc.stats(me()); }
    @GetMapping("/offers") public List<Map<String, Object>> offers() { return svc.offers(me()); }

    @GetMapping("/shops/{partnerId}/link")
    public Map<String, Object> link(@PathVariable UUID partnerId, @RequestParam(required = false) String campaign) { return svc.link(me(), partnerId, campaign); }

    @GetMapping("/commissions")
    public List<Map<String, Object>> commissions(@RequestParam(required = false) UUID partnerId, @RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) { return svc.commissions(me(), partnerId, status, limit); }

    @GetMapping("/payouts") public List<Map<String, Object>> payouts() { return svc.payouts(me()); }
    @PostMapping("/shops/{partnerId}/payouts") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> requestPayout(@PathVariable UUID partnerId, @RequestBody(required = false) NoteReq r) { return svc.requestPayout(me(), partnerId, r == null ? null : r.note()); }
    @PostMapping("/payouts/{id}/cancel") public Map<String, Object> cancel(@PathVariable UUID id) { svc.cancelPayout(me(), id); return Map.of("ok", true); }

    @PostMapping("/shops/{partnerId}/rating") public Map<String, Object> rate(@PathVariable UUID partnerId, @RequestBody RateReq r) { return svc.rateShop(me(), partnerId, r.rating() == null ? 0 : r.rating(), r.comment()); }
    @GetMapping("/shops/{partnerId}/messages") public List<Map<String, Object>> thread(@PathVariable UUID partnerId) { return svc.thread(me(), partnerId); }
    @PostMapping("/shops/{partnerId}/messages") public Map<String, Object> send(@PathVariable UUID partnerId, @RequestBody TextReq r) { svc.send(me(), partnerId, r.body()); return Map.of("ok", true); }
    @GetMapping("/disputes") public List<Map<String, Object>> disputes() { return svc.disputes(me()); }
    @PostMapping("/shops/{partnerId}/disputes") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> dispute(@PathVariable UUID partnerId, @RequestBody DisputeReq r) { svc.openDispute(me(), partnerId, r.commissionId(), r.reason()); return Map.of("ok", true); }

    @GetMapping("/notifications") public Map<String, Object> notifications() { return svc.notifications(me()); }
    @PostMapping("/notifications/read") public Map<String, Object> read() { svc.readNotifications(me()); return Map.of("ok", true); }
}
