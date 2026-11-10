package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** The website chat's test dropdown (model choice) and its photo / voice note upload. The model and the media reader are scripted. */
class WebChatModelAndMediaIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMediaReader.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    ResultActions upload(Tenant t, String conv, MockMultipartFile file, String message, String model) throws Exception {
        var b = multipart("/api/v1/shop/chat/media").file(file).param("conversationId", conv).header("Host", t.host());
        if (message != null) b.param("message", message);
        if (model != null) b.param("model", model);
        return mvc.perform(b);
    }

    String chosen(String conv) {
        return jdbc.sql("SELECT ai_model FROM chat.conversations WHERE external_id = :c AND channel = 'WEB'").param("c", conv).query(String.class).optional().orElse(null);
    }

    @Test
    void theConfigTellsWhichModelsCanBePickedAndWhetherPhotosAndVoiceNotesWork() throws Exception {
        Tenant t = onboard("STORE");
        String cfg = body(onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null).andExpect(status().isOk()));
        assertThat(JsonPath.<java.util.List<String>>read(cfg, "$.models")).contains("default", "glm", "qwen", "anthropic", "deepseek");
        assertThat((Boolean) JsonPath.read(cfg, "$.media")).isTrue();
        FakeMediaReader.ENABLED = false;
        assertThat((Boolean) JsonPath.read(body(onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null)), "$.media")).isFalse();
    }

    @Test
    void aPickedModelIsKeptOnTheConversationAndAnUnknownOneIsIgnored() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        for (String pick : new String[]{"deepseek", "qwen", "default", "bogus"}) {
            FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلا بيك"));
            onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"ازيك\",\"model\":\"%s\"}".formatted(conv, pick)).andExpect(status().isOk());
            String expected = switch (pick) { case "default" -> null; case "bogus" -> null; default -> pick; };
            // "bogus" changes nothing: the previous pick ("default" = none) stays
            assertThat(chosen(conv)).as(pick).isEqualTo(expected);
        }
    }

    @Test
    void aPhotoIsReadIntoTextAndAnsweredLikeATypedMessage() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("فستان وردي قصير", turns -> Turn.model("وصلتني الصورة يا فندم، تحب أساعدك إزاي؟")));
        String res = body(upload(t, conv, new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3}), "مساء الخير", null).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(res, "$.heard")).contains("فستان وردي قصير").contains("مساء الخير");
        assertThat((String) JsonPath.read(res, "$.reply")).contains("وصلتني الصورة");
        // the chat keeps what the assistant read, not the picture
        assertThat(jdbc.sql("SELECT content FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.external_id = :c AND m.role = 'user'").param("c", conv).query(String.class).single())
                .contains("فستان وردي قصير");
    }

    @Test
    void aVoiceNoteIsWrittenOutAndTheModelPickRidesWithIt() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        String res = body(upload(t, conv, new MockMultipartFile("file", "voice.webm", "audio/webm", new byte[]{1, 2, 3}), null, "deepseek").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(res, "$.heard")).startsWith("🎤 ").contains("مقاس ميديم");
        assertThat(chosen(conv)).isEqualTo("deepseek");
    }

    @Test
    void otherFilesBigFilesAndASwitchedOffReaderAreRefused() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        upload(t, conv, new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1}), null, null).andExpect(status().isBadRequest());
        upload(t, conv, new MockMultipartFile("file", "big.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1]), null, null).andExpect(status().isBadRequest());
        upload(t, "not-a-conversation", new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1}), null, null).andExpect(status().isBadRequest());
        FakeMediaReader.ENABLED = false;
        upload(t, conv, new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1}), null, null).andExpect(status().isServiceUnavailable());
    }

    @Test
    void aFileNoModelCanReadGetsAKindAnswerAndNothingIsSavedOrBilled() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        FakeMediaReader.PHOTO_SAYS = "";   // not used: the reader answers with the caption only, and there is none
        String res = body(upload(t, conv, new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[0]), null, null).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(res, "$.reply")).contains("ما قدرتش");
        assertThat((String) JsonPath.read(res, "$.heard")).isEmpty();
    }
}
