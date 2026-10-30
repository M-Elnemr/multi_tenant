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
    public static volatile List<ToolDef> lastTools = List.of();
    public static List<ToolDef> lastTools() { return lastTools; }
    public static final Deque<Function<List<Turn>, Turn>> SCRIPT = new ArrayDeque<>();
    public static final List<ToolResult> RESULTS = new ArrayList<>();

    public static synchronized void reset() { SCRIPT.clear(); RESULTS.clear(); lastSystem = ""; }

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

    @Override
    public synchronized Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
        lastSystem = system;
        lastTools = tools;
        Turn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
        if (last != null && "tool".equals(last.role())) RESULTS.addAll(last.results());
        var f = SCRIPT.poll();
        return f == null ? Turn.model("ok") : f.apply(turns);
    }
}
