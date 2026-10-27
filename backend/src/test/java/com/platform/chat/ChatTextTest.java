package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatTextTest {
    @Test
    void smallTextHelpers() {
        assertThat(ChatService.statedGender("انا راجل مش بنت")).isEqualTo("MALE");
        assertThat(ChatService.statedGender("أنا ست كبيرة")).isEqualTo("FEMALE");
        assertThat(ChatService.statedGender("انت مش راجل")).isNull();
        assertThat(ChatService.genderFromWords(List.of("عايزة فستان", "محتاجة مقاس"))).isEqualTo("FEMALE");
        assertThat(ChatService.genderFromWords(List.of("عاوز بنطلون"))).isEqualTo("MALE");
        assertThat(ChatService.genderFromWords(List.of("عاوز", "عايزة"))).isNull();
        assertThat(ChatService.forChannel("WHATSAPP", "### Title\n**بنطلون** جينز")).isEqualTo("Title\n*بنطلون* جينز");
        assertThat(ChatService.forChannel("INSTAGRAM", "**bold** text")).isEqualTo("bold text");
        assertThat(ChatService.isSummary("ده ملخص الأوردر يا فندم:\n...\nأأكد الأوردر؟ اكتب «تمام»")).isTrue();
        assertThat(ChatService.isSummary("حاضر هجمعلك الاتنين")).isFalse();
    }

    @Test
    void onWhatsAppARequestForAPhoneNumberIsDropped() {
        assertThat(ChatService.forChannel("WHATSAPP", "محتاج منك في رسالة واحدة: الاسم، رقم تليفون عليه واتساب، العنوان بالتفصيل، وطريقة الدفع."))
                .isEqualTo("محتاج منك في رسالة واحدة: الاسم، العنوان بالتفصيل، وطريقة الدفع.");
        assertThat(ChatService.forChannel("WHATSAPP", "رقم الأوردر: ST-1")).isEqualTo("رقم الأوردر: ST-1");
        assertThat(ChatService.forChannel("WEB", "الاسم، رقم تليفون عليه واتساب، العنوان")).contains("رقم تليفون");
    }
}
