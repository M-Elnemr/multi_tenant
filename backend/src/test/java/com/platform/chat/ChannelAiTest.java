package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.chat.AiProvider.AiUnavailableException;
import com.platform.chat.AiProvider.Turn;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ChannelAiTest {
    /** A scripted model: each call returns the next supplier's turn (or throws). */
    static class Scripted implements AiProvider {
        final String code;
        final boolean on;
        final List<Supplier<Turn>> script = new ArrayList<>();
        int calls;

        Scripted(String code, boolean on) { this.code = code; this.on = on; }

        @Override public String code() { return code; }
        @Override public String label() { return code.toUpperCase() + " model"; }
        @Override public boolean available() { return on; }
        @Override public Turn next(String system, List<Turn> turns, List<ToolDef> tools) { calls++; return script.remove(0).get(); }
    }

    static final List<Turn> ASK = List.of(Turn.user("hi"));

    @Test
    void socialChannelsGoToClaudeAndTheWebsiteStaysOnTheDefault() {
        Scripted gemini = new Scripted("gemini", true), claude = new Scripted("anthropic", true);
        ChannelAi ai = new ChannelAi(gemini, claude, "anthropic", "default");
        for (String c : List.of("WHATSAPP", "FACEBOOK", "INSTAGRAM")) assertThat(ai.forChannel(c).code()).isEqualTo("anthropic");
        for (String c : List.of("WEB", "TEST")) assertThat(ai.forChannel(c).code()).isEqualTo("gemini");
        // without a key the routed model is not used at all
        assertThat(new ChannelAi(gemini, new Scripted("anthropic", false), "anthropic", "default").forChannel("WHATSAPP").code()).isEqualTo("gemini");
        // and everything follows the default when nothing is routed
        assertThat(new ChannelAi(gemini, claude, "default", "default").forChannel("WHATSAPP").code()).isEqualTo("gemini");
    }

    @Test
    void aCustomerCanPickAnyOfTheThreeModelsAndTheCommandsAreRecognised() {
        Scripted g31 = new Scripted("gemini", true), g25 = new Scripted("gemini25", true), claude = new Scripted("anthropic", true);
        ChannelAi ai = new ChannelAi(g31, claude, g25, "default", "default");
        assertThat(ai.forChannel("WHATSAPP").code()).isEqualTo("gemini");   // default: Gemini 3.1
        assertThat(ai.forChannel("WHATSAPP", "anthropic").code()).isEqualTo("anthropic");
        assertThat(ai.forChannel("WHATSAPP", "gemini25").code()).isEqualTo("gemini25");
        assertThat(ai.forChannel("WHATSAPP", "gemini").code()).isEqualTo("gemini");
        // a model that is not configured falls back to the default instead of failing the chat
        assertThat(new ChannelAi(g31, claude, new Scripted("gemini25", false), "default", "default").forChannel("WEB", "gemini25").code()).isEqualTo("gemini");
        assertThat(ChannelAi.command("Claude")).isEqualTo("anthropic");
        assertThat(ChannelAi.command("switch to claude")).isEqualTo("anthropic");
        assertThat(ChannelAi.command("gemini 3.1")).isEqualTo("gemini");
        assertThat(ChannelAi.command("Gemini 2.5")).isEqualTo("gemini25");
        assertThat(ChannelAi.command("use gemini 2.5")).isEqualTo("gemini25");
        assertThat(ChannelAi.command("do you have claude shirts")).isNull();
        assertThat(ChannelAi.command("بكام")).isNull();
    }

    @Test
    void aFailedOrEmptyAnswerOnTheFirstRoundIsAnsweredByTheDefaultModel() {
        Scripted gemini = new Scripted("gemini", true), claude = new Scripted("anthropic", true);
        claude.script.add(() -> { throw new AiUnavailableException("credit exhausted"); });
        gemini.script.add(() -> Turn.model("from gemini"));
        var routed = new ChannelAi(gemini, claude, "anthropic", "default").forChannel("WHATSAPP");
        assertThat(routed.next("s", ASK, List.of()).text()).isEqualTo("from gemini");
        assertThat(routed.fellBack()).isTrue();
        assertThat(routed.code()).isEqualTo("gemini");

        // a refusal comes back as an empty answer with no tool call
        Scripted claude2 = new Scripted("anthropic", true), gemini2 = new Scripted("gemini", true);
        claude2.script.add(() -> Turn.model(""));
        gemini2.script.add(() -> Turn.model("second opinion"));
        var r2 = new ChannelAi(gemini2, claude2, "anthropic", "default").forChannel("INSTAGRAM");
        assertThat(r2.next("s", ASK, List.of()).text()).isEqualTo("second opinion");
        assertThat(r2.fellBack()).isTrue();

        // a good answer stays with Claude
        Scripted claude3 = new Scripted("anthropic", true), gemini3 = new Scripted("gemini", true);
        claude3.script.add(() -> Turn.model("from claude"));
        var r3 = new ChannelAi(gemini3, claude3, "anthropic", "default").forChannel("FACEBOOK");
        assertThat(r3.next("s", ASK, List.of()).text()).isEqualTo("from claude");
        assertThat(r3.fellBack()).isFalse();
        assertThat(gemini3.calls).isZero();
    }

    @Test
    void neverSwitchesModelInTheMiddleOfAToolExchange() {
        Scripted gemini = new Scripted("gemini", true), claude = new Scripted("anthropic", true);
        claude.script.add(() -> new Turn("model", "", List.of(new AiProvider.ToolCall("search_products", java.util.Map.of())), List.of(), "raw-claude-blocks"));
        claude.script.add(() -> { throw new AiUnavailableException("overloaded"); });
        var routed = new ChannelAi(gemini, claude, "anthropic", "default").forChannel("WHATSAPP");
        Turn first = routed.next("s", ASK, List.of());
        List<Turn> second = new ArrayList<>(ASK);
        second.add(first);
        second.add(Turn.tool(List.of()));
        // the second round is a tool round: the failure is reported, not answered by another model that could not read Claude's blocks
        assertThatThrownBy(() -> routed.next("s", second, List.of())).isInstanceOf(AiUnavailableException.class);
        assertThat(gemini.calls).isZero();
    }

    @Test
    void afterAFallbackTheRestOfTheReplyStaysOnTheDefaultModel() {
        Scripted gemini = new Scripted("gemini", true), claude = new Scripted("anthropic", true);
        claude.script.add(() -> { throw new AiUnavailableException("down"); });
        gemini.script.add(() -> new Turn("model", "", List.of(new AiProvider.ToolCall("search_products", java.util.Map.of())), List.of(), null));
        gemini.script.add(() -> Turn.model("done"));
        var routed = new ChannelAi(gemini, claude, "anthropic", "default").forChannel("WHATSAPP");
        Turn first = routed.next("s", ASK, List.of());
        List<Turn> next = new ArrayList<>(ASK);
        next.add(first);
        next.add(Turn.tool(List.of()));
        assertThat(routed.next("s", next, List.of()).text()).isEqualTo("done");
        assertThat(claude.calls).isEqualTo(1);
    }
}
