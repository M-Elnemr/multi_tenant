package com.platform.chat;

/** Lets integration tests outside the package drop the cached AI settings after they change the row. */
public final class AiConfigTestAccess {
    private AiConfigTestAccess() {}

    public static void forget() { AiConfigService.forgetCached(); }
}
