package com.platform.chat.unofficial;

import com.platform.chat.unofficial.HumanPolicy.Profile;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The two policy profiles, read from configuration: {@code app.meta.unofficial.*} (Messenger / Instagram manual style) and {@code app.broadcast.whatsapp.*} (own-number WhatsApp).
 * Every key is optional and falls back to the defaults of {@link HumanPolicy}; a cap of 0 switches it off. The WhatsApp gap keeps the owner's 5-15 s from
 * {@code app.broadcast.free-gap-*} (the SegmentService reads those itself), with no hourly or daily cap.
 */
@Component
public class HumanPolicies {
    private final HumanPolicy manual, whatsapp;
    private final boolean whatsappTypingSleep;

    public HumanPolicies(Environment env) {
        this.manual = new HumanPolicy(load(env, "app.meta.unofficial.", HumanPolicy.manualDefaults()));
        this.whatsapp = new HumanPolicy(load(env, "app.broadcast.whatsapp.", HumanPolicy.whatsappDefaults()));
        this.whatsappTypingSleep = Boolean.parseBoolean(env.getProperty("app.broadcast.whatsapp.typing-sleep", "true"));
    }

    public HumanPolicy manual() { return manual; }

    public HumanPolicy whatsapp() { return whatsapp; }

    /** Whether the WhatsApp lane really waits while "typing" (tests switch it off). */
    public boolean whatsappTypingSleep() { return whatsappTypingSleep; }

    static Profile load(Environment e, String k, Profile d) {
        int[] ramp = d.warmupCaps();
        String csv = e.getProperty(k + "warmup-caps");
        if (csv != null) ramp = csv.isBlank() ? new int[0] : Arrays.stream(csv.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
        return new Profile(d.name(), d.logNormal(),
                i(e, k, "gap-min-seconds", d.gapMinSeconds()), i(e, k, "gap-max-seconds", d.gapMaxSeconds()), i(e, k, "jitter-max-seconds", d.jitterMaxSeconds()),
                i(e, k, "break-every-min", d.breakEveryMin()), i(e, k, "break-every-max", d.breakEveryMax()), i(e, k, "break-min-seconds", d.breakMinSeconds()), i(e, k, "break-max-seconds", d.breakMaxSeconds()),
                i(e, k, "hourly-cap", d.hourlyCap()), i(e, k, "max-daily", d.maxDaily()), ramp,
                i(e, k, "active-from-hour", d.activeFromHour()), i(e, k, "active-to-hour", d.activeToHour()),
                i(e, k, "max-chars", d.maxChars()), i(e, k, "max-links", d.maxLinks()), i(e, k, "max-emoji-repeat", d.maxEmojiRepeat()), i(e, k, "min-variants", d.minVariants()),
                i(e, k, "typing-ms-per-char", d.typingMsPerChar()), i(e, k, "typing-base-min-ms", d.typingBaseMinMs()), i(e, k, "typing-base-max-ms", d.typingBaseMaxMs()), i(e, k, "typing-cap-ms", d.typingCapMs()),
                i(e, k, "owner-reply-minutes", d.ownerReplyMinutes()), i(e, k, "max-contact-age-days", d.maxContactAgeDays()), i(e, k, "same-contact-days", d.sameContactDays()), i(e, k, "min-messages", d.minMessages()));
    }

    private static int i(Environment e, String prefix, String key, int def) { return e.getProperty(prefix + key, Integer.class, def); }
}
