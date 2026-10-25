package com.platform.chat;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The platform admin sees how many AI tokens each shop used, on any day, down to a single conversation. */
@RestController
@RequestMapping("/api/v1/platform/ai-usage")
@PreAuthorize("hasAuthority('platform.chat.teach')")
public class PlatformAiUsageController {
    private final AiUsageService usage;

    public PlatformAiUsageController(AiUsageService usage) { this.usage = usage; }

    @GetMapping
    public Map<String, Object> day(@RequestParam(required = false) String day) { return usage.day(day); }

    @GetMapping("/shops/{tenantId}")
    public Map<String, Object> shop(@PathVariable UUID tenantId, @RequestParam(required = false) String day) { return usage.shop(tenantId, day); }

    @GetMapping("/conversations/{id}")
    public Map<String, Object> conversation(@PathVariable UUID id) { return usage.conversation(id); }
}
