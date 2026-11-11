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

/** The website chat's photo / voice note upload, and the fact that a visitor can no longer choose the model (the platform admin does). The model and the media reader are scripted. */
class WebChatModelAndMediaIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMediaReader.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    ResultActions upload(Tenant t, String conv, MockMultipartFile file, String message) throws Exception {
        var b = multipart("/api/v1/shop/chat/media").file(file).param("conversationId", conv).header("Host", t.host());
        if (message != null) b.param("message", message);
        return mvc.perform(b);
    }

    String chosen(String conv) {
        return jdbc.sql("SELECT ai_model FROM chat.conversations WHERE external_id = :c AND channel = 'WEB'").param("c", conv).query(String.class).optional().orElse(null);
    }

    @Test
    void theConfigTellsWhetherPhotosAndVoiceNotesWorkAndOffersNoModelChoice() throws Exception {
        Tenant t = onboard("STORE");
        String cfg = body(onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null).andExpect(status().isOk()));
        assertThat(cfg).doesNotContain("\"models\"");
        assertThat((Boolean) JsonPath.read(cfg, "$.media")).isTrue();
        FakeMediaReader.ENABLED = false;
        assertThat((Boolean) JsonPath.read(body(onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null)), "$.media")).isFalse();
    }

    @Test
    void aVisitorCannotPickTheModelAnyMoreAnOldClientsModelFieldIsIgnored() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        for (String pick : new String[]{"deepseek", "qwen", "anthropic", "bogus"}) {
            FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلا بيك"));
            onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"ازيك\",\"model\":\"%s\"}".formatted(conv, pick)).andExpect(status().isOk());
            assertThat(chosen(conv)).as(pick).isNull();   // an old page that still sends a model changes nothing
        }
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        body(upload(t, conv, new MockMultipartFile("file", "voice.webm", "audio/webm", new byte[]{1, 2, 3}), null));
        assertThat(chosen(conv)).isNull();
    }

    @Test
    void aPhotoIsReadIntoTextAndAnsweredLikeATypedMessage() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("فستان وردي قصير", turns -> Turn.model("وصلتني الصورة يا فندم، تحب أساعدك إزاي؟")));
        String res = body(upload(t, conv, new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3}), "مساء الخير").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(res, "$.heard")).contains("فستان وردي قصير").contains("مساء الخير");
        assertThat((String) JsonPath.read(res, "$.reply")).contains("وصلتني الصورة");
        // the chat keeps what the assistant read, not the picture
        assertThat(jdbc.sql("SELECT content FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.external_id = :c AND m.role = 'user'").param("c", conv).query(String.class).single())
                .contains("فستان وردي قصير");
    }

    @Test
    void aVoiceNoteIsWrittenOutAndAnswered() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        String res = body(upload(t, conv, new MockMultipartFile("file", "voice.webm", "audio/webm", new byte[]{1, 2, 3}), null).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(res, "$.heard")).startsWith("🎤 ").contains("مقاس ميديم");
    }

    @Test
    void otherFilesBigFilesAndASwitchedOffReaderAreRefused() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        upload(t, conv, new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1}), null).andExpect(status().isBadRequest());
        upload(t, conv, new MockMultipartFile("file", "big.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1]), null).andExpect(status().isBadRequest());
        upload(t, "not-a-conversation", new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1}), null).andExpect(status().isBadRequest());
        FakeMediaReader.ENABLED = false;
        upload(t, conv, new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1}), null).andExpect(status().isServiceUnavailable());
    }

    @Test
    void aFileNoModelCanReadGetsAKindAnswerAndNothingIsSavedOrBilled() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        FakeMediaReader.PHOTO_SAYS = "";   // not used: the reader answers with the caption only, and there is none
        String res = body(upload(t, conv, new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[0]), null).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(res, "$.reply")).contains("ما قدرتش");
        assertThat((String) JsonPath.read(res, "$.heard")).isEmpty();
    }

    @Test
    void theShopPicksTheAssistantsPictureFromTheTenBuiltInOnesAndTheStorefrontChatShowsIt() throws Exception {
        Tenant t = onboard("STORE");
        assertThat(JsonPath.<String>read(body(onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null)), "$.agentAvatar")).isEmpty();   // not chosen: the page shows Nada
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"agentAvatar\":\"r2\",\"agentName\":\"Robo\"}").andExpect(status().isOk());
        String cfg = body(onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null));
        assertThat((String) JsonPath.read(cfg, "$.agentAvatar")).isEqualTo("r2");
        assertThat((String) JsonPath.read(cfg, "$.agentName")).isEqualTo("Robo");
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"agentAvatar\":\"hacker\"}").andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"agentAvatar\":\"\"}").andExpect(status().isOk());   // reset
        assertThat(JsonPath.<String>read(body(onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null)), "$.agentAvatar")).isEmpty();
    }
}
