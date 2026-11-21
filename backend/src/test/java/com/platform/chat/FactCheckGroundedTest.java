package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * FactCheck.confirmsGrounded is the POSITIVE confirmation that lets an «answered without looking» reply skip its rewrite: it must say true only when the shop's data confirms the reply, and false for
 * anything it cannot check (so the rewrite stays exactly as before). Fixture: the nile-style shop (canvas sneakers 690: navy / white / black, sizes 40-45, white 42 and 45 out of stock).
 */
class FactCheckGroundedTest {
    static final FactCheck.Shop SHOP = FactCheckTest.fixture("nile-style");

    private boolean ok(String answer) { return FactCheck.confirmsGrounded(answer, SHOP); }

    @Test
    void aReplyWhoseProductPriceColoursAndSizesAreAllTheShopsIsConfirmed() {
        assertThat(ok("كوتشي كانفاس سعره ٦٩٠ جنيه، متاح بثلاث ألوان: أسود، كحلي وأبيض، من مقاس ٤٠ لـ ٤٥.")).isTrue();
        assertThat(ok("كوتشي كانفاس بـ 690 جنيه، وألوانه أسود وكحلي وأبيض 👟")).isTrue();
        assertThat(ok("كوتشي كانفاس متاح بالأسود والكحلي")).isTrue();   // an option claim alone, no price
    }

    @Test
    void aWrongPriceIsNeverConfirmed() {
        assertThat(ok("كوتشي كانفاس سعره ٦٥٠ جنيه، متاح أسود وكحلي")).isFalse();
        assertThat(ok("كوتشي كانفاس سعره ٦٩٠ جنيه ومعاه شحن ٣٠ جنيه")).isFalse();   // an amount that is no price of the products
    }

    @Test
    void aColourTheProductsDoNotHaveIsNeverConfirmed() {
        assertThat(ok("كوتشي كانفاس سعره ٦٩٠ جنيه، متاح أحمر وأسود")).isFalse();
        assertThat(ok("كوتشي كانفاس متاح أخضر")).isFalse();
    }

    @Test
    void aNumberOfUnknownMeaningOrASizeTheProductLacksIsNeverConfirmed() {
        assertThat(ok("كوتشي كانفاس سعره ٦٩٠ جنيه، متاح أسود، مقاس ٤٨")).isFalse();
        assertThat(ok("كوتشي كانفاس متاح أسود وعندنا ٣ قطع منه")).isFalse();
    }

    @Test
    void nothingVerifiableMeansNoConfirmation() {
        assertThat(ok("أهلاً بحضرتك، تحب أساعدك في إيه؟")).isFalse();                       // no product
        assertThat(ok("كوتشي كانفاس موجود عندنا يا فندم")).isFalse();                        // a product, but no claim checked
        assertThat(ok("")).isFalse();
        assertThat(FactCheck.confirmsGrounded(null, SHOP)).isFalse();
        assertThat(FactCheck.confirmsGrounded("كوتشي كانفاس بـ ٦٩٠ جنيه", null)).isFalse();
    }

    @Test
    void anUnknownProductIsNeverConfirmed() {
        assertThat(ok("ساعة ذكية سعرها ٦٩٠ جنيه متاحة أسود")).isFalse();
    }
}
