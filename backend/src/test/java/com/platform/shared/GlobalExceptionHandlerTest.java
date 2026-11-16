package com.platform.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

/** A malformed or wrongly typed JSON body used to come back as 500 «Unexpected error» (found by sending replyToMessageId "abc" to the chat). */
class GlobalExceptionHandlerTest {
    @Test
    void unreadableBodyIsABadRequestNotAServerError() {
        var r = new GlobalExceptionHandler().unreadable(new HttpMessageNotReadableException("bad", new MockHttpInputMessage(new byte[0])));
        assertEquals(400, r.getStatusCode().value());
        assertEquals("INVALID_REQUEST_BODY", r.getBody().get("code"));
    }
}
