package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FallbackMediaReaderTest {
    HttpServer server;
    final List<String> hits = new ArrayList<>();
    int deepseekStatus = 200;
    String deepseekText = "قميص أسود قطن";
    String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ds/chat/completions", ex -> reply(ex, "deepseek", deepseekStatus, deepseekText));
        server.createContext("/qwen/chat/completions", ex -> reply(ex, "qwen", 200, "فستان وردي"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private void reply(com.sun.net.httpserver.HttpExchange ex, String who, int status, String text) throws java.io.IOException {
        hits.add(who);
        ex.getRequestBody().readAllBytes();
        String body = status == 200 ? "{\"choices\":[{\"message\":{\"content\":\"" + text + "\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}" : "{\"error\":\"nope\"}";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private FallbackMediaReader reader(String qwenKey) {
        // Qwen stays the last resort; without a key it only returns the caption
        QwenMediaReader qwen = new QwenMediaReader(mock(AiUsageService.class), qwenKey, "http://127.0.0.1:" + server.getAddress().getPort() + "/qwen", true, "q", "a");
        return new FallbackMediaReader(mock(AiUsageService.class), qwen, true, "glm-key", "", base + "/ds", "ds-model");
    }

    private static MediaReader.Media photo(String caption) { return new MediaReader.Media(MediaReader.Kind.IMAGE, () -> new byte[]{1, 2, 3}, "image/jpeg", caption); }

    @Test
    void deepSeekReadsAPhotoAndAVoiceNoteAndQwenIsNotAsked() {
        String text = reader("").read(UUID.randomUUID(), "WEB", photo(null));
        assertThat(text).isEqualTo(MediaReader.PHOTO + "قميص أسود قطن]");
        deepseekText = "عايز قميص أسود";
        var voice = new MediaReader.Media(MediaReader.Kind.AUDIO, () -> new byte[]{1, 2, 3, 4}, "audio/webm", null);
        assertThat(reader("").read(UUID.randomUUID(), "WEB", voice)).isEqualTo(MediaReader.VOICE + "عايز قميص أسود");
        assertThat(hits).containsExactly("deepseek", "deepseek");
    }

    @Test
    void qwenReadsWhenDeepSeekFailsOrMakesAnExcuse() {
        deepseekStatus = 500;
        assertThat(reader("qwen-key").read(UUID.randomUUID(), "WEB", photo("بكام؟"))).isEqualTo(MediaReader.PHOTO + "فستان وردي]\nبكام؟");
        assertThat(hits).containsExactly("deepseek", "qwen");
        hits.clear();
        deepseekStatus = 200;
        deepseekText = "I'm sorry, I can't see images";
        assertThat(reader("qwen-key").read(UUID.randomUUID(), "WEB", photo(null))).startsWith(MediaReader.PHOTO);
        assertThat(hits).containsExactly("deepseek", "qwen");
    }

    @Test
    void whenNeitherReadsOnlyTheCaptionIsLeft() {
        deepseekStatus = 500;
        assertThat(reader("").read(UUID.randomUUID(), "WEB", photo("بكام؟"))).isEqualTo("بكام؟");   // Qwen has no key
        assertThat(reader("").read(UUID.randomUUID(), "WEB", photo(null))).isNull();
        assertThat(hits).containsExactly("deepseek", "deepseek");
    }

    @Test
    void theBytesAreFetchedOnceWhicheverModelReads() {
        deepseekStatus = 500;
        int[] fetched = {0};
        var media = new MediaReader.Media(MediaReader.Kind.IMAGE, () -> { fetched[0]++; return new byte[]{1}; }, "image/png", null);
        reader("qwen-key").read(UUID.randomUUID(), "WEB", media);
        assertThat(fetched[0]).isEqualTo(1);
    }

    @Test
    void excusesAreNotReadsAndAudioFormatsAreNamed() {
        assertThat(OpenAiMediaReader.unreadable("")).isTrue();
        assertThat(OpenAiMediaReader.unreadable("UNREADABLE")).isTrue();
        assertThat(OpenAiMediaReader.unreadable("لا أستطيع سماع الصوت")).isTrue();
        assertThat(OpenAiMediaReader.unreadable("عايز قميص أسود مقاس لارج")).isFalse();
        assertThat(OpenAiMediaReader.audioFormat("audio/webm")).isEqualTo("webm");
        assertThat(OpenAiMediaReader.audioFormat("audio/mp4")).isEqualTo("mp4");
        assertThat(OpenAiMediaReader.audioFormat("audio/ogg")).isEqualTo("ogg");
        assertThat(OpenAiMediaReader.audioFormat("audio/mpeg")).isEqualTo("mp3");
    }
}
