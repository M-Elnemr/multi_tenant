package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The pure parts of fewer AI steps and the reply cleanups found in the 2026-11-25 test rounds. */
class AiStepsTest {


    @Test
    void theModelGetsShortVariantIdsAndTheCodeKeepsTheWholeOnes() {
        String id = "3f2a9c1e-55aa-4b1c-9d0e-123456789abc";
        Map<String, Object> result = Map.of("products", List.of(Map.of("name", "Mug", "variants", List.of(Map.of("variantId", id, "options", "default")))));
        String sent = ChatService.shortVariantIds(result).toString();
        assertThat(sent).contains("variantId=3f2a9c1e").doesNotContain(id);
        assertThat(result.toString()).contains(id);
        assertThat(ChatService.shortVariantIds(Map.of("variantId", "not-an-id"))).containsEntry("variantId", "not-an-id");
    }

    @Test
    void aKnownNameIsNeverAskedForAgain() {
        assertThat(ChatService.withoutNameAsk("تمام يا أستاذ كريم 👌 عشان أجهزلك الطلب: اسمك ورقم الواتساب والمحافظة والمنطقة؟"))
                .isEqualTo("تمام يا أستاذ كريم 👌 عشان أجهزلك الطلب: رقم الواتساب والمحافظة والمنطقة؟");
        assertThat(ChatService.withoutNameAsk("ابعتلي اسمك، رقم موبايلك، والعنوان")).isEqualTo("ابعتلي رقم موبايلك، والعنوان");
        assertThat(ChatService.withoutNameAsk("محتاج منك:\n- الاسم\n- رقم الواتساب\n- العنوان")).isEqualTo("محتاج منك:\n- رقم الواتساب\n- العنوان");
        assertThat(ChatService.withoutNameAsk("الكارديجان متاح بـ ٦٩٠ جنيه. أتشرف باسم حضرتك؟")).isEqualTo("الكارديجان متاح بـ ٦٩٠ جنيه.");
        assertThat(ChatService.withoutNameAsk("الاسم ده جميل والمنتج متاح")).isEqualTo("الاسم ده جميل والمنتج متاح");
        assertThat(ChatService.withoutNameAsk("محتاج منك: اسمك (كريم تمام؟)، رقم الواتساب، المحافظة والمنطقة")).isEqualTo("محتاج منك: رقم الواتساب، المحافظة والمنطقة");
        assertThat(ChatService.withoutNameAsk("محتاج منك عشان نقفل الطلب: اسمك الأول، رقم الواتساب، والمنطقة")).isEqualTo("محتاج منك عشان نقفل الطلب: رقم الواتساب، والمنطقة");
        assertThat(ChatService.withoutNameAsk("عشان اجهزهالك محتاج: اسمك على الطلبية، رقم الواتساب")).isEqualTo("عشان اجهزهالك محتاج: رقم الواتساب");
        // the name is known from «مساء الخير، انا كريم» (round 2: it was not, so the cleanup never ran)
        assertThat(ChatService.statedName("مساء الخير، انا كريم")).isEqualTo("كريم");
        assertThat(ChatService.statedName("انا منى، الإسكندرية سموحة")).isEqualTo("منى");
        assertThat(ChatService.statedName("مساء الخير، انا مستعجل")).isNull();
        assertThat(ChatService.statedName("انا عايز قميص")).isNull();
    }

    @Test
    void aMisspelledCouponCodeIsWrittenAsTheShopsCode() {
        List<String> codes = List.of("SHAWARMA15", "WELCOME10");
        assertThat(ChatService.withRealCouponCodes("كود SHAWAMA15 بيعمل خصم ١٥٪", codes)).isEqualTo("كود SHAWARMA15 بيعمل خصم ١٥٪");
        assertThat(ChatService.withRealCouponCodes("استخدم WELCOM10", codes)).isEqualTo("استخدم WELCOME10");
        assertThat(ChatService.withRealCouponCodes("كود SHAWARMA15 و USB-C و IPX7 و AMOLED", codes)).isEqualTo("كود SHAWARMA15 و USB-C و IPX7 و AMOLED");
    }
}
