package com.platform.chat;

import com.platform.commerce.StoreContext;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** What the AI seller could not answer, and how many automatic replies the shop has used this month. */
@RestController
@RequestMapping("/api/v1/store/ai")
public class SellerController {
    private final SellerTools seller;

    public SellerController(SellerTools seller) { this.seller = seller; }

    @GetMapping("/usage")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> usage() { return seller.usageView(StoreContext.tenantId()); }

    @GetMapping("/unanswered")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public List<Map<String, Object>> unanswered() { return seller.unanswered(StoreContext.tenantId()); }

    @PostMapping("/unanswered/{id}/resolve")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(@PathVariable long id) { seller.resolve(StoreContext.tenantId(), id); }
}
