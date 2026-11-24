package com.platform.chat;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The platform admin chooses which model answers text, reads photos and voice notes, how the prompt is written, and which Hive keys the requests are spread over. */
@RestController
@RequestMapping("/api/v1/platform/ai-config")
@PreAuthorize("hasAuthority('platform.chat.teach')")
public class PlatformAiConfigController {
    public record KeyRequest(String label, String key) {}
    public record EnabledRequest(boolean enabled) {}

    private final AiConfigService config;

    public PlatformAiConfigController(AiConfigService config) { this.config = config; }

    @GetMapping
    public Map<String, Object> view() { return config.view(); }

    @PutMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> body, Authentication a) { return config.update(body, user(a)); }

    @PostMapping("/keys")
    public Map<String, Object> addKey(@RequestBody KeyRequest r, Authentication a) { return config.addKey(r.label(), r.key(), user(a)); }

    @PatchMapping("/keys/{id}")
    public Map<String, Object> switchKey(@PathVariable String id, @RequestBody EnabledRequest r) { return config.setKeyEnabled(id, r.enabled()); }

    @DeleteMapping("/keys/{id}")
    public Map<String, Object> deleteKey(@PathVariable String id) { return config.deleteKey(id); }

    @PostMapping("/keys/{id}/test")
    public Map<String, Object> testKey(@PathVariable String id) { return config.testKey(id); }

    private static UUID user(Authentication a) { return a != null && a.getPrincipal() instanceof UUID u ? u : null; }
}
