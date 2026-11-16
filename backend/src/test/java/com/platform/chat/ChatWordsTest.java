package com.platform.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** An agreement is not a product: «أيوه» after «تحب أوريك صورها؟» once pulled swimsuits (مايوه) into a bag conversation because the word is a piece of «مايوه». */
class ChatWordsTest {
    @Test
    void agreementWordsAreNotProductWords() {
        for (String w : new String[] {"ايوه", "أيوه", "ايوا", "ايوة", "اكيد", "تمام", "طيب", "yes", "ok"}) assertTrue(ChatService.isChatWord(FastAnswers.norm(w)), w);
    }

    @Test
    void productAndRequestWordsStayAsTheyWere() {
        assertTrue(ChatService.isChatWord("عايز"));
        assertFalse(ChatService.isChatWord("مايوه"));
        assertFalse(ChatService.isChatWord("شنطه"));
    }
}
