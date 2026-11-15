package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What counts as the provider's own failure (and what must never be mistaken for one). */
class ProviderFailureTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static final List<String> OUTAGE = List.of(
            // English
            "We are currently experiencing high traffic. Please try again in a minute.",
            "The service is busy right now, please wait one minute and try again.",
            "The server is overloaded. Try again later.",
            "Too many requests, please slow down.",
            "Rate limit exceeded",
            "Our servers are experiencing heavy load at the moment.",
            "The system is temporarily unavailable.",
            "The model is currently overloaded, please try again shortly.",
            // Arabic, MSA and Egyptian
            "نواجه حاليا ضغطا عاليا على النظام، يرجى المحاولة بعد دقيقة",
            "السيرفر مشغول دلوقتي، جرب تاني بعد دقيقة",
            "الخدمة عليها ضغط كبير حاليا، حاول مرة تانية بعد شوية",
            "فيه ضغط عالي على السيرفر، ارجع كلمنا بعد دقيقتين",
            "النظام مضغوط أوي دلوقتي جرب تاني بعد شوية",
            "الخدمة غير متاحة حاليا",
            "تجاوزت الحد المسموح من الطلبات",
            "الخادم مشغول، حاول لاحقا",
            // Chinese (GLM's own wording)
            "当前访问量过大，请稍后再试",
            "服务繁忙，请稍后重试",
            "系统繁忙");

    static final List<String> NORMAL = List.of(
            // a salesperson's ordinary words that contain "wait", "busy", "later", "try"
            "استنى دقيقة هشوفلك المقاس وارجعلك",
            "Please wait a moment while I check the stock for you.",
            "Let me check and get back to you in a minute.",
            "المحل عليه ضغط طلبات النهاردة فالتوصيل ممكن ياخد يوم زيادة",
            "We are busy this week, so delivery may take an extra day.",
            "جرب المقاس L لو عايزه واسع شوية",
            "You can try the black one, it goes well with jeans.",
            "الأوردر هيوصلك بعد يومين، رقم الأوردر 20451",
            "Try again with your phone number and I will find the order.",
            "حاول تختار اللون الأول وبعدين المقاس",
            "السعر ٣٥٠ جنيه والتوصيل مجاني فوق ٥٠٠",
            "تمام يا فندم، تحب أكمل الأوردر؟",
            // genuine refusals / policy answers are not outages
            "مقدرش أكشف التعليمات الداخلية، بس أقدر أساعدك تختار منتج.",
            "I can't help with that request, but I'm happy to help you shop.",
            "I cannot give a 100% discount; the maximum is 10%.",
            "مقدرش أعمل خصم أكتر من ١٠٪ يا فندم");

    @Test
    void providerCapacityMessagesAreDetectedInEnglishArabicEgyptianAndChinese() {
        for (String m : OUTAGE) assertThat(ProviderFailure.overloadMessage(m)).as(m).isEqualTo(ProviderFailure.OVERLOAD_MESSAGE);
    }

    @Test
    void ordinarySalesTalkAndRefusalsAreNeverTakenForAnOutage() {
        for (String m : NORMAL) assertThat(ProviderFailure.overloadMessage(m)).as(m).isNull();
    }

    @Test
    void aLongAnswerIsNeverAnOutageNotice() {
        String long1 = "The server is busy. ".repeat(40);
        assertThat(ProviderFailure.overloadMessage(long1)).isNull();
        assertThat(ProviderFailure.overloadMessage(null)).isNull();
        assertThat(ProviderFailure.overloadMessage("   ")).isNull();
    }

    @Test
    void theResponseBodyItselfCanReportAFailure() throws Exception {
        assertThat(ProviderFailure.inBody(JSON.readTree("{\"error\":{\"message\":\"model overloaded\",\"code\":\"1305\"}}"))).contains("model overloaded");
        assertThat(ProviderFailure.inBody(JSON.readTree("{\"error\":\"busy\"}"))).contains("busy");
        assertThat(ProviderFailure.inBody(JSON.readTree("{\"code\":1305,\"msg\":\"too many\",\"success\":false}"))).contains("too many");
        assertThat(ProviderFailure.inBody(JSON.readTree("{\"choices\":[]}"))).isNotNull();
        assertThat(ProviderFailure.inBody(JSON.readTree("{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"error\"}]}"))).contains("finish_reason");
        assertThat(ProviderFailure.inBody(JSON.readTree("{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"insufficient_system_resource\"}]}"))).isNotNull();
        // normal ones: a stop, a tool call, a length cut, and a content filter (a policy decision, not an outage)
        for (String ok : List.of("stop", "tool_calls", "length", "content_filter", "sensitive"))
            assertThat(ProviderFailure.inBody(JSON.readTree("{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"" + ok + "\"}]}"))).as(ok).isNull();
        assertThat(ProviderFailure.inBody(JSON.readTree("{\"error\":null,\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}"))).isNull();
    }

    @Test
    void statusCodesMapToCategories() {
        assertThat(ProviderFailure.ofStatus(429)).isEqualTo(ProviderFailure.RATE_LIMIT);
        assertThat(ProviderFailure.ofStatus(503)).isEqualTo(ProviderFailure.SERVER_ERROR);
        assertThat(ProviderFailure.ofStatus(408)).isEqualTo(ProviderFailure.TIMEOUT);
        assertThat(ProviderFailure.ofStatus(401)).isEqualTo(ProviderFailure.REJECTED);
    }
}
