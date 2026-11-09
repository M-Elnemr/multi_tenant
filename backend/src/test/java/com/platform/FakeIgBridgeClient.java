package com.platform;

import com.platform.chat.unofficial.IgBridgeClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for the ig-bridge reads (followers, posts, comments) in tests. Nothing here ever talked to a real Instagram account. */
@Component
@Primary
public class FakeIgBridgeClient extends IgBridgeClient {
    /** Followers served page by page (cursor = the page index). */
    public static final List<List<User>> FOLLOWER_PAGES = new ArrayList<>();
    public static final List<Media> MEDIA = new ArrayList<>();
    public static final Map<String, List<Comment>> COMMENTS = new HashMap<>();
    public static volatile String ERROR = null;
    public static volatile int FOLLOWER_CALLS = 0;

    public FakeIgBridgeClient() { super("", ""); }

    public static void reset() { FOLLOWER_PAGES.clear(); MEDIA.clear(); COMMENTS.clear(); ERROR = null; FOLLOWER_CALLS = 0; }

    public static User user(String pk, String username) { return new User(pk, username, "Name " + username, false); }

    @Override
    public UsersPage followers(UUID tenant, int amount, String cursor) {
        FOLLOWER_CALLS++;
        if (ERROR != null) return new UsersPage(false, ERROR, List.of(), null);
        int i = cursor == null ? 0 : Integer.parseInt(cursor);
        if (i >= FOLLOWER_PAGES.size()) return new UsersPage(true, null, List.of(), null);
        return new UsersPage(true, null, FOLLOWER_PAGES.get(i), i + 1 < FOLLOWER_PAGES.size() ? String.valueOf(i + 1) : null);
    }

    @Override
    public MediaPage recentMedia(UUID tenant, int amount) { return ERROR != null ? new MediaPage(false, ERROR, List.of()) : new MediaPage(true, null, List.copyOf(MEDIA)); }

    @Override
    public CommentsPage comments(UUID tenant, String mediaId, int amount) {
        return ERROR != null ? new CommentsPage(false, ERROR, List.of()) : new CommentsPage(true, null, COMMENTS.getOrDefault(mediaId, List.of()));
    }
}
