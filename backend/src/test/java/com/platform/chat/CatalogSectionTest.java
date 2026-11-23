package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The catalog block is found by its own heading line, not by the first mention of the words in the rules paragraph. */
class CatalogSectionTest {
    static final String SYSTEM = "RULES: SHOP CATALOG LIST is the complete truth: answer from it, never from memory.\n\nMore rules.\n"
            + "\nSHOP CATALOG LIST (everything this shop sells; if a customer asks for any of it, we HAVE it):\n- Bags: 2 products, 620-700\nALL PRODUCTS (the complete list):\nشنطة رياضة 620\n\nOTHER BLOCK\n";

    @Test
    void theLookupReturnsTheCatalogNotTheRulesSentence() {
        String bySection = ChatService.section(SYSTEM, ChatService.CATALOG_HEADING, 9000);
        assertThat(bySection).as("the old lookup found the rules sentence").contains("is the complete truth").doesNotContain("شنطة رياضة 620");
        String block = ChatService.catalogSection(SYSTEM, 9000);
        assertThat(block).contains("SHOP CATALOG LIST (everything this shop sells").contains("شنطة رياضة 620").doesNotContain("is the complete truth").doesNotContain("OTHER BLOCK");
        assertThat(ChatService.catalogSection("no catalog here", 100)).isEmpty();
        assertThat(ChatService.catalogSection(null, 100)).isEmpty();
    }
}
