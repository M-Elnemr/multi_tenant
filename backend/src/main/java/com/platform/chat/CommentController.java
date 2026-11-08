package com.platform.chat;

import com.platform.commerce.StoreContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Comments on the shop's Facebook / Instagram posts and ads: list, reply publicly, send a private message, AI suggestion, ignore, and the automation settings. */
@RestController
@RequestMapping("/api/v1/store/comments")
public class CommentController {
    private final CommentService comments;

    public CommentController(CommentService comments) { this.comments = comments; }

    public record TextRequest(String text) {}
    public record SuggestRequest(Boolean privately) {}
    public record SettingsRequest(String publicReply, String stampText, Boolean dmEnabled, Boolean dmOnlyQuestions) {}

    @GetMapping
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> list(@RequestParam(required = false) String platform, @RequestParam(required = false) String status, @RequestParam(required = false) String post,
                                    @RequestParam(required = false) String q, @RequestParam(required = false) Integer cursor) {
        return comments.list(StoreContext.tenantId(), platform, status, post, q, cursor);
    }

    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> reply(@PathVariable UUID id, @RequestBody TextRequest r) { return comments.reply(StoreContext.tenantId(), id, r.text()); }

    @PostMapping("/{id}/dm")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> dm(@PathVariable UUID id, @RequestBody(required = false) TextRequest r) { return comments.dm(StoreContext.tenantId(), id, r == null ? null : r.text()); }

    @PostMapping("/{id}/suggest")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> suggest(@PathVariable UUID id, @RequestBody(required = false) SuggestRequest r) { return comments.suggest(StoreContext.tenantId(), id, r != null && Boolean.TRUE.equals(r.privately())); }

    @PostMapping("/{id}/ignore")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> ignore(@PathVariable UUID id) { return comments.ignore(StoreContext.tenantId(), id); }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> settings() { return comments.settings(StoreContext.tenantId()); }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> save(@RequestBody SettingsRequest r) { return comments.saveSettings(StoreContext.tenantId(), r.publicReply(), r.stampText(), r.dmEnabled(), r.dmOnlyQuestions()); }
}
