package com.platform.chat;

/** Lets tests outside the package make every AiConfigService read the settings again at once. */
public final class AiConfigTestAccess {
    private AiConfigTestAccess() {}

    public static void forget() { AiConfigService.forgetCached(); }
}
