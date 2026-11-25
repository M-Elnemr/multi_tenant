package com.platform;

import com.platform.chat.AiProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for Gemini in tests: each test queues the model's next turns and can read what the model was told and what the tools answered. */
@Component
@Primary
public class FakeAiProvider implements AiProvider {
    public static volatile String lastSystem = "";
    /** The system message exactly as sent (without the reply's context that travels with the newest message). */
    public static volatile String lastRawSystem = "";
    public static volatile List<ToolDef> lastTools = List.of();
    public static List<ToolDef> lastTools() { return lastTools; }
    public static final Deque<Function<List<Turn>, Turn>> SCRIPT = new ArrayDeque<>();
    public static final List<ToolResult> RESULTS = new ArrayList<>();

    public static synchronized void reset() { SCRIPT.clear(); RESULTS.clear(); FROM_CHAT.clear(); lastSystem = ""; lastRawSystem = ""; }

    /** The results the chat reply already recorded, as the model is then sent them: not added a second time when the next call shows them. */
    private static final List<ToolResult> FROM_CHAT = new ArrayList<>();

    static {   // every tool result of a chat reply as the code keeps it, also when no model call follows it (fewer AI steps ends the reply after a summary)
        com.platform.chat.ChatService.TOOL_RESULTS_FOR_TESTS = r -> { synchronized (FakeAiProvider.class) { RESULTS.add(r); FROM_CHAT.add(new ToolResult(r.name(), com.platform.chat.ChatService.shortVariantIds(r.result()))); } };
    }

    /**
     * A scripted turn for one chat only: when another conversation (a background job of the app) reaches it first, it goes back to the front of the queue and that caller gets «ok».
     * {@code marker} is any text of the chat it belongs to.
     */
    public static Function<List<Turn>, Turn> only(String marker, Function<List<Turn>, Turn> f) {
        return new Function<>() {
            @Override public Turn apply(List<Turn> turns) {
                boolean mine = turns.stream().anyMatch(t -> t.text() != null && t.text().contains(marker));
                if (mine) return f.apply(turns);
                SCRIPT.addFirst(this);
                return Turn.model("ok");
            }
        };
    }

    public static Turn call(String tool, java.util.Map<String, Object> args) { return new Turn("model", "", List.of(new ToolCall(tool, args)), List.of(), null); }

    static final String CONTEXT_OPEN = "⟦CONTEXT FOR THIS REPLY", CONTEXT_CLOSE = "⟦END OF CONTEXT⟧", NEWEST = "The customer's newest message:\n";

    /** The customer's own words of a turn: without the reply's context the system puts in front of the newest message. */
    public static String customerText(Turn t) {
        String x = t == null || t.text() == null ? "" : t.text();
        int at = x.startsWith(CONTEXT_OPEN) ? x.indexOf(NEWEST) : -1;
        return at < 0 ? x : x.substring(at + NEWEST.length());
    }

    @Override
    public synchronized Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
        // everything the shop's system told the model: the system message plus this reply's context, which travels in front of the customer's newest message
        String context = turns.stream().filter(t -> "user".equals(t.role()) && t.text() != null && t.text().startsWith(CONTEXT_OPEN)).map(Turn::text)
                .map(x -> x.substring(0, Math.max(0, x.indexOf(CONTEXT_CLOSE)))).reduce((a, b) -> b).orElse("");
        lastRawSystem = system;
        lastSystem = context.isEmpty() ? system : system + "\n" + context;
        lastTools = tools;
        Turn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
        if (last != null && "tool".equals(last.role()))   // other tool loops (the copilot...) are seen here; a chat reply's results were recorded where they ran
            for (ToolResult r : last.results()) { if (!FROM_CHAT.remove(r)) RESULTS.add(r); }
        var f = SCRIPT.poll();
        return f == null ? Turn.model("ok") : f.apply(turns);
    }
}
