package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/** The platform default is the conditional review; the policy the running instance reports is the one it really uses; the rollback is one setting, and a value nobody recognises never weakens the review. */
class AiReviewPolicyDefaultTest extends IntegrationTestBase {
    @Autowired ChatService chat;

    @Test
    void conditionalIsTheDefaultAndTheReportedPolicyIsTheEffectiveOne() {
        assertThat(chat.reviewPolicy()).containsEntry("mode", "conditional").containsEntry("configured", "conditional");
    }

    @Test
    void alwaysIsTheRollbackAndAnUnknownValueMeansAlways() {
        Object before = ReflectionTestUtils.getField(chat, "reviewMode");
        try {
            for (String v : new String[] {"always", "ALWAYS", "alwayz", "off", "", "  "}) {
                ReflectionTestUtils.setField(chat, "reviewMode", v);
                assertThat(chat.reviewPolicy()).as(v).containsEntry("mode", "always");
            }
            ReflectionTestUtils.setField(chat, "reviewMode", " Conditional ");
            assertThat(chat.reviewPolicy()).containsEntry("mode", "conditional");
        } finally { ReflectionTestUtils.setField(chat, "reviewMode", before); }
    }
}
