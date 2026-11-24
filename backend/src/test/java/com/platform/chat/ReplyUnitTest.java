package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReplyUnitTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode j(String s) { try { return JSON.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    static MessageReplies.Quoted q(String sender, String kind, String text, boolean snapshot) { return new MessageReplies.Quoted(snapshot ? null : 7L, sender, kind, text, null, OffsetDateTime.parse("2026-11-10T12:05:00+02:00"), snapshot); }

    // ---- what each platform sends ------------------------------------------------------------------------------------

    @Test
    void whatsAppCloudReplyIsTheContextIdAndAButtonTapIsNotAReply() {
        assertThat(MetaChannelService.whatsappReply(j("{\"type\":\"text\",\"text\":{\"body\":\"x\"},\"context\":{\"from\":\"1\",\"id\":\"wamid.ABC\"}}")).providerMid()).isEqualTo("wamid.ABC");
        assertThat(MetaChannelService.whatsappReply(j("{\"type\":\"text\",\"text\":{\"body\":\"x\"}}"))).as("no context: nothing is invented").isNull();
        assertThat(MetaChannelService.whatsappReply(j("{\"type\":\"interactive\",\"interactive\":{\"type\":\"button_reply\"},\"context\":{\"id\":\"wamid.BTN\"}}"))).isNull();
        assertThat(MetaChannelService.whatsappReply(j("{\"type\":\"text\",\"context\":{\"id\":\"\"}}"))).isNull();
    }

    @Test
    void messengerAndInstagramReplyIsReplyToMidAndAStoryReplyHasNoInventedContent() {
        assertThat(MetaChannelService.messengerReply(j("{\"mid\":\"m1\",\"text\":\"x\",\"reply_to\":{\"mid\":\"mid.OLD\"}}")).providerMid()).isEqualTo("mid.OLD");
        assertThat(MetaChannelService.messengerReply(j("{\"mid\":\"m1\",\"text\":\"x\"}"))).isNull();
        ReplyRef story = MetaChannelService.messengerReply(j("{\"reply_to\":{\"story\":{\"url\":\"https://cdn/x\",\"id\":\"s1\"}}}"));
        assertThat(story.providerMid()).isNull();
        assertThat(story.quote().kind()).isEqualTo("story");
        assertThat(story.quote().text()).isNull();
    }

    @Test
    void ownNumberWhatsAppCarriesTheQuoteAndKnowsWhoWroteIt() {
        ReplyRef shop = EvolutionService.replyOf(j("{\"extendedTextMessage\":{\"text\":\"x\",\"contextInfo\":{\"stanzaId\":\"S1\",\"participant\":\"15550001@s.whatsapp.net\",\"quotedMessage\":{\"conversation\":\"الأسعار تبدأ من الخمسين\"}}}}"), "201055550000@s.whatsapp.net");
        assertThat(shop.providerMid()).isEqualTo("S1");
        assertThat(shop.quote().sender()).isEqualTo("shop");
        assertThat(shop.quote().text()).isEqualTo("الأسعار تبدأ من الخمسين");
        ReplyRef self = EvolutionService.replyOf(j("{\"extendedTextMessage\":{\"text\":\"x\",\"contextInfo\":{\"stanzaId\":\"S2\",\"participant\":\"201055550000@s.whatsapp.net\",\"quotedMessage\":{\"conversation\":\"مقاس L\"}}}}"), "201055550000@s.whatsapp.net");
        assertThat(self.quote().sender()).isEqualTo("customer");
        ReplyRef image = EvolutionService.replyOf(j("{\"conversation\":\"x\",\"imageMessage\":{\"caption\":\"y\",\"contextInfo\":{\"stanzaId\":\"S3\",\"participant\":\"15550001@s.whatsapp.net\",\"quotedMessage\":{\"imageMessage\":{\"caption\":\"جاكيت أسود\"}}}}}"), "201055550000@s.whatsapp.net");
        assertThat(image.quote().hasMedia()).isTrue();
        assertThat(image.quote().text()).isEqualTo("جاكيت أسود");
        ReplyRef pic = EvolutionService.replyOf(j("{\"extendedTextMessage\":{\"text\":\"x\",\"contextInfo\":{\"stanzaId\":\"S4\",\"quotedMessage\":{\"imageMessage\":{}}}}}"), "201055550000@s.whatsapp.net");
        assertThat(pic.quote().kind()).as("an image with no caption: described, not invented").isEqualTo("attachment");
        assertThat(EvolutionService.replyOf(j("{\"conversation\":\"plain\"}"), "201055550000@s.whatsapp.net")).isNull();
    }

    @Test
    void sentMessageIdsAreReadFromTheAnswersOfThePlatforms() {
        assertThat(MetaChannelService.midOf(Map.of("messages", List.of(Map.of("id", "wamid.X"))))).isEqualTo("wamid.X");
        assertThat(MetaChannelService.midOf(Map.of("message_id", "mid.Y"))).isEqualTo("mid.Y");
        assertThat(MetaChannelService.midOf(Map.of("ok", true))).isNull();
        assertThat(MetaChannelService.midOf(null)).isNull();
        assertThat(EvolutionService.keyId(Map.of("key", Map.of("id", "BAE5")))).isEqualTo("BAE5");
        assertThat(EvolutionService.keyId(Map.of())).isNull();
    }

    // ---- a burst keeps the reference of each line ------------------------------------------------------------------------

    @Test
    void aBurstOfLinesKeepsEachLinesPlatformIdAndItsOwnReplyReference() throws Exception {
        var timers = Executors.newSingleThreadScheduledExecutor();
        try {
            var burst = new InboundBurst(timers, Runnable::run, 80, 500);
            var done = new CountDownLatch(1);
            var got = new ArrayList<Object>();
            var text = new String[1];
            java.util.function.BiConsumer<String, List<Object>> fire = (t, metas) -> { text[0] = t; got.addAll(metas); done.countDown(); };
            burst.add("k", "بكام ده", new ChatService.InboundMeta("m1", ReplyRef.ofMid("old-1", null)), fire);
            burst.add("k", "وفيه أزرق؟", new ChatService.InboundMeta("m2", null), fire);
            burst.add("k", "والتاني بكام", new ChatService.InboundMeta("m3", ReplyRef.ofMid("old-2", null)), fire);
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(text[0]).isEqualTo("بكام ده\nوفيه أزرق؟\nوالتاني بكام");
            var in = ChatService.input(got);
            assertThat(in.mids()).containsExactly("m1", "m2", "m3");
            assertThat(in.refs()).hasSize(2);
            assertThat(in.refs().get(0).providerMid()).isEqualTo("old-1");
            assertThat(in.refs().get(0).line()).as("the first reference belongs to the FIRST line").isEqualTo("بكام ده");
            assertThat(in.refs().get(1).providerMid()).isEqualTo("old-2");
            assertThat(in.refs().get(1).line()).as("the second to the THIRD line, not to every line").isEqualTo("والتاني بكام");
        } finally { timers.shutdownNow(); }
    }

    @Test
    void linesArrivingConcurrentlyAreAllKeptOnceAndAnsweredOnce() throws Exception {
        var timers = Executors.newSingleThreadScheduledExecutor();
        var pool = Executors.newFixedThreadPool(8);
        try {
            var burst = new InboundBurst(timers, Runnable::run, 150, 2000);
            var fired = new java.util.concurrent.atomic.AtomicInteger();
            var seen = new java.util.concurrent.CopyOnWriteArrayList<Object>();
            var done = new CountDownLatch(1);
            java.util.function.BiConsumer<String, List<Object>> fire = (t, metas) -> { fired.incrementAndGet(); seen.addAll(metas); done.countDown(); };
            var start = new CountDownLatch(1);
            for (int th = 0; th < 8; th++) { int id = th; pool.submit(() -> { try { start.await(); } catch (InterruptedException e) { return; } for (int i = 0; i < 20; i++) burst.add("k", "line " + id + "-" + i, new ChatService.InboundMeta("mid-" + id + "-" + i, i % 2 == 0 ? ReplyRef.ofMid("q-" + id + "-" + i, null) : null), fire); }); }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(400);   // nothing may fire a second time
            assertThat(fired.get()).as("one answer for the whole burst").isEqualTo(1);
            var in = ChatService.input(seen);
            assertThat(in.mids()).hasSize(160).doesNotHaveDuplicates();
            assertThat(in.refs()).hasSize(80);
            for (var r : in.refs()) assertThat(r.line()).as("each reference keeps its own line").isEqualTo("line " + r.providerMid().substring(2));
        } finally { timers.shutdownNow(); pool.shutdownNow(); }
    }

    // ---- what the assistant is told --------------------------------------------------------------------------------------

    @Test
    void theBlockLabelsTheOriginalAndKeepsIdsOutAndTellsTheAssistantToUseCurrentData() {
        var item = new ReplyContext.Item(q("assistant", "text", "عندنا جاكيت قطن بالأسود بـ 1200 جنيه", false), List.of(q("customer", "text", "عايز جاكيت", false), q("assistant", "text", "عندنا جاكيت قطن بالأسود بـ 1200 جنيه", false)), List.of("جاكيت قطن [cotton-jacket]"), false);
        String b = ReplyContext.block(List.of(item), false, "هل ده متاح بالأزرق؟");
        assertThat(b).contains("MESSAGE BEING REPLIED TO:").contains("ORIGINAL MESSAGE SENDER: you, the shop's assistant").contains("SENT: 2026-11-10 12:05")
                .contains("ORIGINAL MESSAGE CONTENT: «عندنا جاكيت قطن بالأسود بـ 1200 جنيه»").contains("THE PRODUCT THIS MESSAGE IS ABOUT").contains("[cotton-jacket]")
                .contains("RELEVANT CONVERSATION CONTEXT").contains("- customer: «عايز جاكيت»").contains("CUSTOMER'S LATEST MESSAGE (the reply itself): «هل ده متاح بالأزرق؟»")
                .contains("CURRENT BUSINESS STATE").contains("is OLD").contains("CURRENT result").contains("Never create or change an order twice");
        assertThat(b).doesNotContainPattern("\\bid\\b *[=:] *\\d").doesNotContain("message_id");
        // inside the history window the neighbours are not repeated
        assertThat(ReplyContext.block(List.of(new ReplyContext.Item(item.quoted(), item.around(), List.of(), true)), false, "x")).doesNotContain("RELEVANT CONVERSATION CONTEXT");
    }

    @Test
    void sendersImagesStoriesSnapshotsAndMissingOnesAreWordedHonestly() {
        assertThat(ReplyContext.block(List.of(new ReplyContext.Item(q("owner", "text", "هنشحنه بكرة", false), List.of(), List.of(), true)), false, null)).contains("the shop team (a person, not you)");
        assertThat(ReplyContext.block(List.of(new ReplyContext.Item(q("shop", "story", null, true), List.of(), List.of(), false)), false, null)).contains("[a story the shop posted]").contains("no longer stored");
        assertThat(ReplyContext.block(List.of(new ReplyContext.Item(q("shop", "attachment", "", true), List.of(), List.of(), false)), false, null)).contains("(no text)");
        String missing = ReplyContext.block(List.of(), true, "وده بكام؟");
        assertThat(missing).contains("IS NOT AVAILABLE").contains("Never guess what it said").contains("never mention ids").doesNotContain("ORIGINAL MESSAGE CONTENT");
        assertThat(ReplyContext.block(List.of(), false, "x")).as("no reply, no block").isEmpty();
        String two = ReplyContext.block(List.of(new ReplyContext.Item(q("assistant", "text", "الأول", false), List.of(), List.of(), true), new ReplyContext.Item(q("assistant", "text", "التاني", false), List.of(), List.of(), true)), false, "x");
        assertThat(two).contains("(1 of 2)").contains("(2 of 2)");
    }

    @Test
    void aBurstNamesTheLineOfEachQuoteInOrderAndSaysWhichLinesQuoteNothing() {
        var a = new ReplyContext.Item(q("assistant", "text", "الجاكيت الأسود", false), List.of(), List.of(), true, "بكام ده");
        var b = new ReplyContext.Item(q("assistant", "text", "الشنطة البني", false), List.of(), List.of(), true, "والتاني");
        String two = ReplyContext.block(List.of(a, b), false, "بكام ده\nوفيه أزرق؟\nوالتاني");
        assertThat(two).contains("(1 of 2), quoted by the customer line «بكام ده»").contains("(2 of 2), quoted by the customer line «والتاني»");
        assertThat(two.indexOf("الجاكيت الأسود")).as("in the order of the lines").isLessThan(two.indexOf("الشنطة البني"));
        assertThat(two).contains("do not apply a quote to a line that has none");   // 3 lines, 2 quotes: the middle line quotes nothing
        // the same original quoted by two lines: both lines are named
        String same = ReplyContext.block(List.of(new ReplyContext.Item(a.quoted(), List.of(), List.of(), true, "بكام"), new ReplyContext.Item(a.quoted(), List.of(), List.of(), true, "وبالأزرق؟")), false, "بكام\nوبالأزرق؟");
        assertThat(same).contains("«بكام»").contains("«وبالأزرق؟»").doesNotContain("do not apply a quote");
        // a single-line message: no line labels at all
        assertThat(ReplyContext.block(List.of(new ReplyContext.Item(a.quoted(), List.of(), List.of(), true, "بكام ده")), false, "بكام ده")).doesNotContain("quoted by the customer line");
    }

    @Test
    void aVagueQuestionAboutAQuotedProductKeepsTheProductInTheSearch() {
        try {
            ChatService.REPLY_PRODUCTS.set(List.of("جاكيت قطن"));
            assertThat(ChatService.withReplyProduct("هل بينكمش بعد الغسيل؟")).isEqualTo("جاكيت قطن هل بينكمش بعد الغسيل؟");
            assertThat(ChatService.withReplyProduct("جاكيت  قطن بيتغسل؟")).as("already names it").isEqualTo("جاكيت  قطن بيتغسل؟");
            ChatService.REPLY_PRODUCTS.set(List.of());
            assertThat(ChatService.withReplyProduct("any question")).isEqualTo("any question");
        } finally { ChatService.REPLY_PRODUCTS.remove(); }
        assertThat(ChatService.withReplyProduct("no reply in this reply")).isEqualTo("no reply in this reply");
    }

    @Test
    void aQuoteIsClippedAndNeverBlowsUpThePrompt() {
        assertThat(MessageReplies.clip("a".repeat(2000), 600)).hasSize(600).endsWith("…");
        assertThat(MessageReplies.clip(null, 600)).isNull();
    }
}
