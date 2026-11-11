package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Benchmark 2026-11-10, scenario 931: «حي» inside the name «فتحي» was read as the address word «حي». The words are now matched whole. */
class DetailsPatternsTest {
    static boolean address(String s) { return ChatService.ADDRESS_WORDS.matcher(s).find(); }

    @Test
    void namesThatContainAnAddressWordAreNotAddressWords() {
        for (String name : new String[] {"فتحي", "محمد فتحي", "صحي", "سيحي", "برجاوي", "برجس", "دورة", "دوري", "Mostafa St", "adapt", "floors", "شقيق", "مدينتي"})
            assertThat(address("انا " + name)).as(name).isFalse();
    }

    @Test
    void realAddressWordsStillMatchWithTheirPrefixes() {
        for (String part : new String[] {"شارع التحرير", "بشارع التحرير", "في الشارع الرئيسي".replace("الشارع", "شارع"), "عمارة ٥", "عمارة5", "الدور ٢", "شقة ٣", "حي الدقي", "بحي الدقي", "ش. التحرير", "5 Tahrir St. Dokki", "Tahrir street", "apt 4", "floor 2", "برج السلام", "مدينة نصر", "منطقة ٦"})
            assertThat(address(part)).as(part).isTrue();
    }

    @Test
    void aNameWithoutCommasStopsAtTheFirstStopWord() {
        var m = ChatService.NAME_SAID.matcher("انا فتحي ساكن القاهرة");
        assertThat(m.find()).isTrue();
        assertThat(m.group(1).trim()).startsWith("فتحي");
    }

    @Test
    void pickupAndCardWordsAreWholeWords() {
        assertThat(ChatService.PICKUP_WORDS.matcher("انا مستلم").find()).isFalse();
        assertThat(ChatService.PICKUP_WORDS.matcher("collection").find()).isFalse();
        for (String p : new String[] {"هستلم من الفرع", "هاجي استلم", "هستلم بنفسي", "اريد استلام", "pick up", "من المحل"})
            assertThat(ChatService.PICKUP_WORDS.matcher(FastAnswers.norm(p)).find()).as(p).isTrue();
        assertThat(ChatService.CARD_WORDS.matcher("عايز كارتون").find()).isFalse();
        assertThat(ChatService.CARD_WORDS.matcher("cardigan").find()).isFalse();
        for (String p : new String[] {"بالفيزا", "كارت", "بطاقة", "visa", "بالكارت"}) assertThat(ChatService.CARD_WORDS.matcher(p).find()).as(p).isTrue();
    }
}
