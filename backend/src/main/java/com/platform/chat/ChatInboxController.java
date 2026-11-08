package com.platform.chat;

import com.platform.commerce.StoreContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The shop's unified inbox: every chat with filters, one chat with its timeline, owner replies, takeover / resume, close, notes, saved replies and AI Assist. */
@RestController
@RequestMapping("/api/v1/store/chat")
public class ChatInboxController {
    private final ChatInboxService inbox;

    public ChatInboxController(ChatInboxService inbox) { this.inbox = inbox; }

    public record ReplyRequest(String message, UUID imageFileId) {}
    public record PauseRequest(Integer hours) {}
    public record NoteRequest(String body) {}
    public record UnhappyRequest(Boolean on) {}
    public record BulkRequest(List<UUID> ids, String action) {}
    public record AssistRequest(String action, String draft) {}
    public record SavedReplyRequest(String title, String body) {}

    private static UUID me(Authentication a) { return (UUID) a.getPrincipal(); }

    /**
     * The inbox. New screens pass {@code page=true} and get {data, total, nextCursor}; without it the old list (an array, needs-you first) is returned for the mobile app.
     */
    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Object list(@RequestParam(defaultValue = "false") boolean needsYou, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                       @RequestParam(defaultValue = "false") boolean page, @RequestParam(required = false) List<String> status, @RequestParam(required = false) List<String> ai,
                       @RequestParam(required = false) List<String> channel, @RequestParam(required = false) String since, @RequestParam(required = false) List<String> customer,
                       @RequestParam(required = false) String q, @RequestParam(required = false) String sort, @RequestParam(required = false) Integer cursor, @RequestParam(required = false) Integer limit) {
        if (!page) return inbox.list(StoreContext.tenantId(), needsYou, from, to);
        return inbox.search(StoreContext.tenantId(), new ChatInboxService.Filter(status, ai, channel, since, from, to, customer, q, sort, cursor, limit, needsYou));
    }

    @GetMapping("/conversations/needs-you-count")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> needsYouCount() { return inbox.counts(StoreContext.tenantId()); }

    @GetMapping("/conversations/counts")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> counts() { return inbox.counts(StoreContext.tenantId()); }

    @GetMapping("/conversations/{id}")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> get(@PathVariable UUID id, @RequestParam(required = false) Long before, @RequestParam(defaultValue = "true") boolean read) {
        return inbox.get(StoreContext.tenantId(), id, before, read);
    }

    @PostMapping("/conversations/{id}/reply")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> reply(@PathVariable UUID id, @RequestBody ReplyRequest r, Authentication a) { return inbox.reply(StoreContext.tenantId(), me(a), id, r.message(), r.imageFileId()); }

    @PostMapping("/conversations/{id}/takeover")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> takeover(@PathVariable UUID id, Authentication a) { return inbox.takeover(StoreContext.tenantId(), me(a), id); }

    @PostMapping("/conversations/{id}/pause")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> pause(@PathVariable UUID id, @RequestBody(required = false) PauseRequest r, Authentication a) {
        return inbox.pause(StoreContext.tenantId(), me(a), id, r == null ? null : r.hours());
    }

    @PostMapping("/conversations/{id}/resume")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> resume(@PathVariable UUID id, Authentication a) { return inbox.resume(StoreContext.tenantId(), me(a), id); }

    @PostMapping("/conversations/{id}/done")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> done(@PathVariable UUID id) { return inbox.done(StoreContext.tenantId(), id); }

    @PostMapping("/conversations/{id}/close")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> close(@PathVariable UUID id, Authentication a) { return inbox.close(StoreContext.tenantId(), me(a), id); }

    @PostMapping("/conversations/{id}/reopen")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> reopen(@PathVariable UUID id, Authentication a) { return inbox.reopen(StoreContext.tenantId(), me(a), id); }

    @PostMapping("/conversations/{id}/read")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) { inbox.markRead(StoreContext.tenantId(), id, true); }

    @PostMapping("/conversations/{id}/unread")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unread(@PathVariable UUID id) { inbox.markRead(StoreContext.tenantId(), id, false); }

    @PostMapping("/conversations/{id}/unhappy")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> unhappy(@PathVariable UUID id, @RequestBody UnhappyRequest r, Authentication a) { return inbox.unhappy(StoreContext.tenantId(), me(a), id, Boolean.TRUE.equals(r.on())); }

    @PostMapping("/conversations/bulk")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> bulk(@RequestBody BulkRequest r, Authentication a) { return Map.of("changed", inbox.bulk(StoreContext.tenantId(), me(a), r.ids(), r.action())); }

    @GetMapping("/conversations/{id}/notes")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public List<Map<String, Object>> notes(@PathVariable UUID id) { return inbox.notes(StoreContext.tenantId(), id); }

    @PostMapping("/conversations/{id}/notes")
    @PreAuthorize("hasAuthority('ai.manage')")
    public List<Map<String, Object>> addNote(@PathVariable UUID id, @RequestBody NoteRequest r, Authentication a) { return inbox.addNote(StoreContext.tenantId(), me(a), id, r.body()); }

    @DeleteMapping("/conversations/{id}/notes/{noteId}")
    @PreAuthorize("hasAuthority('ai.manage')")
    public List<Map<String, Object>> deleteNote(@PathVariable UUID id, @PathVariable long noteId) { return inbox.deleteNote(StoreContext.tenantId(), id, noteId); }

    @PostMapping("/conversations/{id}/assist")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> assist(@PathVariable UUID id, @RequestBody AssistRequest r) { return inbox.assist(StoreContext.tenantId(), id, r.action(), r.draft()); }

    // ---- saved replies ----------------------------------------------------------------------------------------------------

    @GetMapping("/saved-replies")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public List<Map<String, Object>> savedReplies() { return inbox.savedReplies(StoreContext.tenantId()); }

    @PostMapping("/saved-replies")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addReply(@RequestBody SavedReplyRequest r) { return inbox.saveReply(StoreContext.tenantId(), null, r.title(), r.body()); }

    @PutMapping("/saved-replies/{id}")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> editReply(@PathVariable UUID id, @RequestBody SavedReplyRequest r) { return inbox.saveReply(StoreContext.tenantId(), id, r.title(), r.body()); }

    @DeleteMapping("/saved-replies/{id}")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReply(@PathVariable UUID id) { inbox.deleteReply(StoreContext.tenantId(), id); }
}
