package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** An order item is the customer's when they named it, even loosely; a suggestion they never named is not (exam 2026-11-25, «بسبوسة بالقشطة»). */
class OrderItemsTest {
    private final List<FactCheck.Named> names = List.of(new FactCheck.Named("b", "بسبوسة بالقشطة"), new FactCheck.Named("k", "كنافة بالقشطة"),
            new FactCheck.Named("s1", "ساندوتش زنجر"), new FactCheck.Named("s2", "ساندوتش فلافل"), new FactCheck.Named("s3", "ساندوتش كفتة"), new FactCheck.Named("m", "Mug 5f2a"));

    @Test
    void aLooselyNamedProductIsTheCustomersAndAWordManyProductsShareIsNotEnough() {
        assertThat(ChatService.namedLoosely("i want 12 mugs", "Mug 5f2a", names)).isTrue();
        assertThat(ChatService.namedLoosely(FastAnswers.norm("عايز البسبوسة"), "بسبوسة بالقشطة", names)).isTrue();
        assertThat(ChatService.namedLoosely(FastAnswers.norm("والكنافة كمان"), "كنافة بالقشطة", names)).isTrue();
        assertThat(ChatService.namedLoosely(FastAnswers.norm("عايز رز بلبن سادة عايزها بالمكسرات مش سادة"), "بسبوسة بالقشطة", names)).isFalse();
        assertThat(ChatService.namedLoosely(FastAnswers.norm("عايز ساندوتش زنجر"), "ساندوتش فلافل", names)).isFalse();
    }
}
