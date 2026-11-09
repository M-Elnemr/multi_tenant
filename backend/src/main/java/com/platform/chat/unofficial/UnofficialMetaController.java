package com.platform.chat.unofficial;

import com.platform.commerce.StoreContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** The shop's unofficial Messenger / Instagram sender: status, connect (Instagram: username + password; Messenger: cookies + Page id; no approval step), wipe, disable / enable. Never returns a credential. */
@RestController
@RequestMapping("/api/v1/store/unofficial-meta")
public class UnofficialMetaController {
    private static final String MANAGE = "hasAuthority('growth.manage')";

    /** The password is masked in toString so a stray log line can never print it. */
    public record ConnectReq(String username, String password, String totpSecret, Boolean acceptedRisk, String cookies, String pageId) {
        @Override public String toString() { return "ConnectReq[username=" + username + ", password=***, totpSecret=***, cookies=***, pageId=" + pageId + "]"; }
    }

    private final UnofficialMetaService service;

    public UnofficialMetaController(UnofficialMetaService service) { this.service = service; }

    private static UUID user(Authentication a) { return (UUID) a.getPrincipal(); }

    @GetMapping
    @PreAuthorize(MANAGE)
    public Map<String, Object> overview() { return service.overview(StoreContext.tenantId()); }

    @PostMapping("/{platform}/connect")
    @PreAuthorize(MANAGE)
    public Map<String, Object> connect(@PathVariable String platform, @RequestBody ConnectReq r, Authentication a) {
        if ("FACEBOOK".equals(UnofficialMetaService.platformOf(platform))) return service.connectCookies(StoreContext.tenantId(), user(a), platform, r.cookies(), r.pageId());
        return service.connect(StoreContext.tenantId(), user(a), platform, r.username(), r.password(), r.totpSecret(), Boolean.TRUE.equals(r.acceptedRisk()));
    }

    @DeleteMapping("/{platform}")
    @PreAuthorize(MANAGE)
    public Map<String, Object> wipe(@PathVariable String platform, Authentication a) { return service.wipe(StoreContext.tenantId(), user(a), platform); }

    @PostMapping("/{platform}/disable")
    @PreAuthorize(MANAGE)
    public Map<String, Object> disable(@PathVariable String platform, Authentication a) { return service.disable(StoreContext.tenantId(), user(a), platform); }

    @PostMapping("/{platform}/enable")
    @PreAuthorize(MANAGE)
    public Map<String, Object> enable(@PathVariable String platform, Authentication a) { return service.enable(StoreContext.tenantId(), user(a), platform); }
}
