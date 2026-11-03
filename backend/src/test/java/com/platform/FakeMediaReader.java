package com.platform;

import com.platform.chat.MediaReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for the photo and voice reader: a photo becomes a fixed description, a voice note a fixed sentence; every read and download is recorded. */
@Component
@Primary
public class FakeMediaReader implements MediaReader {
    public static final List<String> DOWNLOADS = new ArrayList<>();
    public static volatile String PHOTO_SAYS = "فستان وردي قصير", VOICE_SAYS = "عايز الفستان ده مقاس ميديم";
    public static volatile boolean ENABLED = true;

    public static synchronized void reset() { DOWNLOADS.clear(); PHOTO_SAYS = "فستان وردي قصير"; VOICE_SAYS = "عايز الفستان ده مقاس ميديم"; ENABLED = true; }

    @Override public boolean enabled() { return ENABLED; }

    @Override
    public String read(UUID tenantId, String channel, Media media) {
        byte[] bytes = media.bytes().get();
        if (bytes == null || bytes.length == 0) return media.caption();
        return media.kind() == Kind.IMAGE ? PHOTO + PHOTO_SAYS + "]" + (media.caption() == null ? "" : "\n" + media.caption()) : VOICE + VOICE_SAYS;
    }

    @Override
    public synchronized byte[] download(String url, String bearerToken, int maxBytes) {
        DOWNLOADS.add(url + " " + bearerToken);
        return new byte[]{1, 2, 3};
    }
}
