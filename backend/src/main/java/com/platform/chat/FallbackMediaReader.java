package com.platform.chat;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one {@link MediaReader} bean, what every channel injects: a customer's photo or voice note goes to DeepSeek V4.1 Flash (served by Hive, the same model that chats); when it cannot
 * read it (error, empty answer, "I can't see images") to Qwen, the reader the WhatsApp and Meta channels used before, so nothing gets worse.
 */
@Component
public class FallbackMediaReader implements MediaReader {
    private static final Logger log = LoggerFactory.getLogger(FallbackMediaReader.class);
    private final OpenAiMediaReader deepseek;
    private final QwenMediaReader qwen;

    @org.springframework.beans.factory.annotation.Autowired
    public FallbackMediaReader(AiUsageService usage, @Value("${app.ai.media.enabled:true}") boolean on, @Value("${app.ai.glm.api-key:}") String glmKey,
                               @Value("${app.ai.deepseek.api-key:}") String deepseekKey, @Value("${app.ai.deepseek.base-url:https://api-cdn.thehive.ai/api/v3}") String deepseekUrl,
                               @Value("${app.ai.media.deepseek-model:${app.ai.deepseek.model:deepseek-ai/deepseek-v4.1-flash-1017}}") String deepseekModel,
                               @Value("${app.ai.qwen.api-key:}") String qwenKey, @Value("${app.ai.qwen.base-url:https://dashscope-intl.aliyuncs.com/compatible-mode/v1}") String qwenUrl,
                               @Value("${app.ai.media.vision-model:qwen3.7-flash}") String qwenVision, @Value("${app.ai.media.asr-model:qwen3-asr-flash}") String qwenAsr) {
        this(usage, new QwenMediaReader(usage, qwenKey, qwenUrl, on, qwenVision, qwenAsr), on, glmKey, deepseekKey, deepseekUrl, deepseekModel);
    }

    /** For tests: the Qwen reader is given. */
    FallbackMediaReader(AiUsageService usage, QwenMediaReader qwen, boolean on, String glmKey, String deepseekKey, String deepseekUrl, String deepseekModel) {
        this.qwen = qwen;
        this.deepseek = new OpenAiMediaReader(usage, "deepseek", deepseekKey == null || deepseekKey.isBlank() ? glmKey : deepseekKey, deepseekUrl, deepseekModel, on, false);   // DeepSeek V4.1 Flash on Hive takes images and text only: tested 2026-11-10, audio (ogg, wav, mp3) answers HTTP 500, so a voice note goes straight to Qwen
    }

    @Override public boolean enabled() { return deepseek.enabled() || qwen.enabled(); }

    @Override
    public String read(UUID tenantId, String channel, Media media) {
        // the bytes are fetched once, whichever model reads them
        byte[][] box = new byte[1][];
        Media once = new Media(media.kind(), () -> box[0] != null ? box[0] : (box[0] = media.bytes().get()), media.mime(), media.caption());
        if (deepseek.enabled()) {
            String text = deepseek.readText(tenantId, channel, once);
            if (text != null) return text;
            log.info("deepseek did not read the {}; trying Qwen", media.kind());
        }
        return qwen.read(tenantId, channel, once);
    }

    @Override public byte[] download(String url, String bearerToken, int maxBytes) { return qwen.download(url, bearerToken, maxBytes); }
}
