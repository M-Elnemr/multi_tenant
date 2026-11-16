package com.platform.chat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The part of the assistant's instructions that says WHICH earlier message the customer is answering, with its real content (found by the backend even when it is far older than the
 * history window). It is written into the per-message end of the system prompt, never into the customer's own turn: the code that checks quantities, prices and names reads the
 * customer's words, and a quoted old price must not be mistaken for something the customer just said. Internal ids never appear in it.
 */
final class ReplyContext {
    private ReplyContext() {}

    /** One quoted message with the messages beside it and the products its text names. */
    record Item(MessageReplies.Quoted quoted, List<MessageReplies.Quoted> around, List<String> products, boolean insideHistory, String line) {
        Item(MessageReplies.Quoted quoted, List<MessageReplies.Quoted> around, List<String> products, boolean insideHistory) { this(quoted, around, products, insideHistory, null); }
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Cairo"));

    static String sender(String s) {
        return switch (s == null ? "" : s) {
            case "customer" -> "the customer (their own earlier message)";
            case "owner" -> "the shop team (a person, not you)";
            case "assistant" -> "you, the shop's assistant";
            default -> "the shop";
        };
    }

    private static String shortSender(String s) { return switch (s == null ? "" : s) { case "customer" -> "customer"; case "owner" -> "shop team"; case "assistant" -> "you"; default -> "shop"; }; }

    private static String content(MessageReplies.Quoted q) {
        String text = q.text() == null ? "" : q.text().strip();
        String media = "image".equals(q.kind()) ? "[an image was attached] " : "story".equals(q.kind()) ? "[a story the shop posted] " : "";
        if (text.isEmpty()) return media.isEmpty() ? "(no text)" : media.strip();
        return media + "«" + text + "»";
    }

    /** The block, or "" when the customer's message replies to nothing. {@code unavailable}: they replied to a message we cannot find or read. */
    static String block(List<Item> items, boolean unavailable, String latest) {
        if (items.isEmpty() && !unavailable) return "";
        StringBuilder b = new StringBuilder("\nTHE CUSTOMER IS REPLYING TO A SPECIFIC EARLIER MESSAGE (they used «reply» on it). Their latest message is about THAT message, not necessarily about the one just before it.\n");
        int n = 0;
        int lines = latest == null ? 0 : (int) latest.lines().filter(l -> !l.isBlank()).count();
        boolean burst = lines > 1;   // several customer lines in one turn: each reference belongs to ITS line
        for (Item it : items) {
            var q = it.quoted();
            String head = items.size() > 1 ? "MESSAGE BEING REPLIED TO (" + (++n) + " of " + items.size() + ")" : "MESSAGE BEING REPLIED TO";
            if (burst && it.line() != null && !it.line().isBlank()) head += ", quoted by the customer line «" + MessageReplies.clip(it.line(), 120) + "»";
            b.append(head).append(":\n");
            b.append("  ORIGINAL MESSAGE SENDER: ").append(sender(q.sender())).append("\n");
            if (q.at() != null) b.append("  SENT: ").append(WHEN.format(q.at())).append(" (Cairo time)\n");
            b.append("  ORIGINAL MESSAGE CONTENT: ").append(content(q)).append("\n");
            if (it.products().size() == 1)   // resolved by code from the stored message, not by the model's reading of a long chat
                b.append("  THE PRODUCT THIS MESSAGE IS ABOUT (resolved by the system from the stored original; the customer's latest message means THIS product, not one from later in the chat): ").append(it.products().get(0)).append("\n");
            else if (it.products().size() > 1)
                b.append("  PRODUCTS NAMED IN IT (look them up by this name or slug; if the latest message does not say which one, ask ONE short question): ").append(String.join("; ", it.products())).append("\n");
            if (q.fromSnapshot()) b.append("  (the original is no longer stored; this is the copy the platform or the chat kept when the customer replied)\n");
            if (!it.around().isEmpty() && !it.insideHistory()) {
                b.append("RELEVANT CONVERSATION CONTEXT (the messages next to it, oldest first):\n");
                for (var m : it.around()) b.append("  - ").append(shortSender(m.sender())).append(": ").append(content(m)).append("\n");
            }
        }
        if (burst && !items.isEmpty() && items.stream().anyMatch(i -> i.line() != null && !i.line().isBlank()) && (items.size() < lines || unavailable))
            b.append("Only the customer lines named above quote an earlier message; the customer's other lines do not reply to anything: do not apply a quote to a line that has none.\n");
        if (unavailable) b.append("A MESSAGE THE CUSTOMER REPLIED TO IS NOT AVAILABLE (deleted, too old to find, or never seen by this system). Never guess what it said and never mention ids or errors. If their message cannot be understood without it, ask ONE short question: which product or which message do they mean.\n");
        if (latest != null && !latest.isBlank()) b.append("CUSTOMER'S LATEST MESSAGE (the reply itself): «").append(MessageReplies.clip(latest.strip(), 300)).append("»\n");
        b.append("""
                HOW TO USE THIS (strict):
                - Understand the latest message against the ORIGINAL MESSAGE FIRST: «this», «it», "the first one", «نفس المقاس», a bare «أسود» / «XL» / «ايوه» / a price offer answer THAT message. If it was a question you asked, the customer is answering it; if it listed several products, "the second one" is counted in its order; if it quoted a price, a bargaining reply is about that product.
                - The original is OLD. Prices, stock, offers and delivery in it may have changed. Before you promise or state any price, stock, discount or delivery, call search_products / get_product and use the CURRENT result (the tools and ORDER ON FILE are the CURRENT BUSINESS STATE); if it differs from the original, say so plainly.
                - Never invent anything the original does not say (materials, waterproofing, sizes...): use shop_knowledge / get_product, and say you cannot confirm what is not documented. When you search or call shop_knowledge, include the product's name from the original message.
                - If the original does not identify what the customer means, ask ONE short question; do not ask about anything the original already answers.
                - Never create or change an order twice: an order that is already on file stays one order.
                """);
        return b.toString();
    }
}
