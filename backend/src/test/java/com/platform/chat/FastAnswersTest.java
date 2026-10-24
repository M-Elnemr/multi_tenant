package com.platform.chat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FastAnswersTest {
    @Test
    void helloAtTheStartAndThanksLaterNeedNoModel() {
        assertTrue(FastAnswers.answer("السلام عليكم", true, null).isPresent());
        assertEquals("أهلاً من المحل", FastAnswers.answer("مرحبا!", true, "أهلاً من المحل").get());
        assertTrue(FastAnswers.answer("Hello", true, null).get().startsWith("Hello"));
        assertTrue(FastAnswers.answer("شكراً", false, null).isPresent());
    }

    @Test
    void anythingReal_goesToTheModel() {
        assertTrue(FastAnswers.answer("مرحبا عايز فستان", true, null).isEmpty());
        assertTrue(FastAnswers.answer("hello", false, null).isEmpty());
        assertTrue(FastAnswers.answer("شكرا بس السعر كام", false, null).isEmpty());
        assertTrue(FastAnswers.answer("شكرا", true, null).isEmpty());
    }
}
