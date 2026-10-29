package com.platform.chat;

import java.util.UUID;

/** A chat the shop should look at itself: an angry customer, someone asking for a person, or the assistant could not answer. The owner is told once per chat until they handle it. */
public record ChatNeedsHuman(UUID tenantId, UUID conversationId, String channel, String reason, String customerText) {}
