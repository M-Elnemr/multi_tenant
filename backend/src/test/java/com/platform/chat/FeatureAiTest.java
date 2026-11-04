package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureAiTest {
    static AiProvider model(String code, boolean fails) {
        return new AiProvider() {
            @Override public String code() { return code; }
            @Override public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
                if (fails) throw new AiUnavailableException(code + " down", null);
                return Turn.model(code + " answered");
            }
        };
    }

    @Test
    void qwenAnswersAndGlmStandsInWhenItFails() {
        var ok = new FeatureAi(model("qwen", false), model("glm", false));
        assertThat(ok.next("s", List.of(AiProvider.Turn.user("hi")), List.of()).text()).isEqualTo("qwen answered");
        assertThat(FeatureAi.takeAnsweredBy()).isEqualTo("qwen");
        var down = new FeatureAi(model("qwen", true), model("glm", false));
        assertThat(down.next("s", List.of(AiProvider.Turn.user("hi")), List.of()).text()).isEqualTo("glm answered");
        assertThat(FeatureAi.takeAnsweredBy()).isEqualTo("glm");
        assertThat(FeatureAi.takeAnsweredBy()).isNull();
    }

    @Test
    void chatRoutingKeepsPlainGlmAsItsDefault() {
        AiProvider glm = model("glm", false), qwen = model("qwen", false);
        var routing = new ChannelAi(new FeatureAi(qwen, glm), model("anthropic", false), glm, qwen, "qwen", "qwen");
        assertThat(routing.backupFor(qwen)).isSameAs(glm);
    }
}
