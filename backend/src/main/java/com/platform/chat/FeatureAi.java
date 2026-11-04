package com.platform.chat;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The model of the AI features that have no model of their own (shop builder, translation, product import, Copilot, marketing text, broadcasts): one model first and another
 * company's model when it fails, so an outage of one never stops them. Chat routing still sees the plain {@link #backup()} as "the default model" (see {@link ChannelAi}).
 */
public final class FeatureAi implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(FeatureAi.class);
    /** Which model answered the last call on this thread, so its usage is billed at that model's price. */
    private static final ThreadLocal<String> ANSWERED = new ThreadLocal<>();

    private final AiProvider primary;
    private final AiProvider backup;

    public FeatureAi(AiProvider primary, AiProvider backup) { this.primary = primary; this.backup = backup; }

    public AiProvider backup() { return backup; }

    /** The code of the model that answered the last call on this thread (and forgets it), or null. */
    public static String takeAnsweredBy() { String c = ANSWERED.get(); ANSWERED.remove(); return c; }

    @Override public String code() { return primary.code(); }
    @Override public String label() { return primary.label() + " (+" + backup.label() + ")"; }
    @Override public boolean available() { return primary.available() || backup.available(); }

    @Override
    public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
        if (primary.available()) {
            try {
                Turn t = primary.next(system, turns, tools);
                ANSWERED.set(primary.code());
                return t;
            } catch (AiUnavailableException e) {
                if (!backup.available()) throw e;
                log.warn("{} failed for a feature, {} answers instead: {}", primary.label(), backup.label(), e.getMessage());
            }
        }
        Turn t = backup.next(system, turns, tools);
        ANSWERED.set(backup.code());
        return t;
    }
}
