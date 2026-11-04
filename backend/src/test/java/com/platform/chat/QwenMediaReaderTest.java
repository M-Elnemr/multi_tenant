package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class QwenMediaReaderTest {
    @Test
    void theTypeIsCleanedAndGuessedWhenMissing() {
        assertThat(QwenMediaReader.mime(new MediaReader.Media(MediaReader.Kind.AUDIO, () -> new byte[0], "audio/ogg; codecs=opus", null))).isEqualTo("audio/ogg");
        assertThat(QwenMediaReader.mime(new MediaReader.Media(MediaReader.Kind.IMAGE, () -> new byte[0], null, null))).isEqualTo("image/jpeg");
        assertThat(QwenMediaReader.mime(new MediaReader.Media(MediaReader.Kind.AUDIO, () -> new byte[0], "", null))).isEqualTo("audio/ogg");
    }

    @Test
    void withoutAKeyOrSwitchedOffOnlyTheCaptionIsKeptAndNothingIsFetched() {
        boolean[] fetched = {false};
        var media = new MediaReader.Media(MediaReader.Kind.IMAGE, () -> { fetched[0] = true; return new byte[]{1}; }, "image/jpeg", "بكام ده؟");
        var noKey = new QwenMediaReader(null, "", "https://example.invalid/v1", true, "qwen3.7-flash", "qwen3-asr-flash");
        var off = new QwenMediaReader(null, "key", "https://example.invalid/v1", false, "qwen3.7-flash", "qwen3-asr-flash");
        assertThat(noKey.enabled()).isFalse();
        assertThat(off.enabled()).isFalse();
        assertThat(noKey.read(UUID.randomUUID(), "WHATSAPP", media)).isEqualTo("بكام ده؟");
        assertThat(off.read(UUID.randomUUID(), "WHATSAPP", media)).isEqualTo("بكام ده؟");
        assertThat(fetched[0]).isFalse();
    }

    @Test
    void aWhatsAppVoiceNoteKnowsItsLength() throws Exception {
        byte[] ogg = getClass().getResourceAsStream("/media/voice.ogg").readAllBytes();
        assertThat(QwenMediaReader.oggSeconds(ogg)).isBetween(3, 6);
        assertThat(QwenMediaReader.oggSeconds(new byte[]{1, 2, 3})).isNull();
    }

    @Test
    void aDescriptionIsOneLineWithoutChineseSlips() {
        assertThat(QwenMediaReader.clean("رفوف عليها ملابس، وش领军 معرّقة.\nمفيش أسعار")).isEqualTo("رفوف عليها ملابس، وش معرّقة. مفيش أسعار");
    }
}
