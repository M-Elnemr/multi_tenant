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
    private final QuestionService questions;

    public SellerController(SellerTools seller, QuestionService questions) { this.seller = seller; this.questions = questions; }

    public record AnswerRequest(String answer) {}

    /** Questions the assistant handed to the owner: {@code status} OPEN, ANSWERED, DISMISSED or empty for all. */
    @GetMapping("/questions")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public List<Map<String, Object>> questions(@RequestParam(required = false) String status) { return questions.list(StoreContext.tenantId(), status); }

    @GetMapping("/questions/open-count")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> openCount() { return Map.of("open", questions.openCount(StoreContext.tenantId())); }

    @GetMapping("/questions/{id}")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> question(@PathVariable long id) { return questions.get(StoreContext.tenantId(), id); }

    @PostMapping("/questions/{id}/answer")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> answer(@PathVariable long id, @RequestBody AnswerRequest r, org.springframework.security.core.Authentication a) {
        return questions.answer(StoreContext.tenantId(), (java.util.UUID) a.getPrincipal(), id, r.answer());
    }

    @PostMapping("/questions/{id}/retry")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> retry(@PathVariable long id) { return questions.retry(StoreContext.tenantId(), id); }

    @PostMapping("/questions/{id}/dismiss")
    @PreAuthorize("hasAuthority('ai.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable long id) { questions.dismiss(StoreContext.tenantId(), id); }

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
