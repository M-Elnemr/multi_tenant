package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Owner's live report 2026-11-21: a reply to the shop's product card reached the assistant with NO quote (production had 0 quotes in 398 WhatsApp customer messages). The quote is now found wherever
 * the payload keeps it: on the message type, in a wrapper, or at the top of the event data; a quote nested inside the quoted message is never taken as ours.
 */
class EvolutionQuoteShapesTest {
    static final ObjectMapper J = new ObjectMapper();
    static final String QUOTED = "{\"extendedTextMessage\":{\"text\":\"كوتشي كانفاس\\n690 جنيه\\nhttps://x/y\"}}";

    private ReplyRef of(String eventData) throws Exception { return EvolutionService.replyOf(J.readTree(eventData), "201001234567@s.whatsapp.net"); }

    @Test
    void theUsualShapeStillWorks() throws Exception {
        var r = of("{\"key\":{},\"message\":{\"extendedTextMessage\":{\"text\":\"دا الوانه ايه\",\"contextInfo\":{\"stanzaId\":\"ABC\",\"participant\":\"2010@s.whatsapp.net\",\"quotedMessage\":" + QUOTED + "}}}}");
        assertThat(r).isNotNull();
        assertThat(r.providerMid()).isEqualTo("ABC");
        assertThat(r.quote().text()).contains("كوتشي كانفاس");
    }

    @Test
    void aContextInfoAtTheTopOfTheEventDataIsFound() throws Exception {
        var r = of("{\"key\":{},\"message\":{\"conversation\":\"دا الوانه ايه\"},\"contextInfo\":{\"stanzaId\":\"TOP\",\"quotedMessage\":" + QUOTED + "}}");
        assertThat(r).isNotNull();
        assertThat(r.providerMid()).isEqualTo("TOP");
        assertThat(r.quote().text()).contains("كوتشي كانفاس");
    }

    @Test
    void aWrappedMessageIsFound() throws Exception {
        var r = of("{\"key\":{},\"message\":{\"ephemeralMessage\":{\"message\":{\"extendedTextMessage\":{\"text\":\"دا الوانه ايه\",\"contextInfo\":{\"stanzaId\":\"WRAP\",\"quotedMessage\":" + QUOTED + "}}}}}}");
        assertThat(r).isNotNull();
        assertThat(r.providerMid()).isEqualTo("WRAP");
    }

    @Test
    void aQuoteInsideTheQuotedMessageIsNotOurs() throws Exception {
        var r = of("{\"key\":{},\"message\":{\"extendedTextMessage\":{\"text\":\"دا الوانه ايه\",\"contextInfo\":{\"stanzaId\":\"OURS\",\"quotedMessage\":{\"extendedTextMessage\":{\"text\":\"x\",\"contextInfo\":{\"stanzaId\":\"THEIRS\"}}}}}}}");
        assertThat(r.providerMid()).isEqualTo("OURS");
    }

    @Test
    void noQuoteGivesNothing() throws Exception {
        assertThat(of("{\"key\":{},\"message\":{\"conversation\":\"سلام\"}}")).isNull();
    }
}
