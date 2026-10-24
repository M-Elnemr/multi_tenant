package com.platform.copilot;

import com.platform.commerce.StoreContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** The owner's copilot: ask about the business, read the three daily actions, forecasts and price ideas. */
@RestController
@RequestMapping("/api/v1/store/copilot")
@PreAuthorize("hasAuthority('copilot.use')")
public class CopilotController {
    public record AskReq(String question) {}

    private final CopilotService copilot;
    private final CopilotData data;

    public CopilotController(CopilotService copilot, CopilotData data) {
        this.copilot = copilot;
        this.data = data;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false, defaultValue = "ar") String lang) {
        UUID t = StoreContext.tenantId();
        return Map.of("available", copilot.available(), "actions", copilot.dailyActions(t, lang), "reorder", data.reorderSuggestions(t),
                "prices", data.priceSuggestions(t, data.maxDiscountPct(t)), "week", data.period(t, 7));
    }

    @GetMapping("/messages")
    public List<Map<String, Object>> messages(Authentication a) { return copilot.history(StoreContext.tenantId(), (UUID) a.getPrincipal()); }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody AskReq r, Authentication a) { return Map.of("answer", copilot.ask(StoreContext.tenantId(), (UUID) a.getPrincipal(), r.question())); }

    @DeleteMapping("/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(Authentication a) { copilot.clear(StoreContext.tenantId(), (UUID) a.getPrincipal()); }
}
