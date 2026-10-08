package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Clinics must say their specialty and shops their category when they sign up; both can pick "Other" and type a name. */
class CategoryRegistrationIntegrationTest extends IntegrationTestBase {
    ResultActions register(String type, String extraJson) throws Exception {
        String slug = (type.equals("STORE") ? "shop" : "clinic") + uniq();
        String body = "{\"type\":\"%s\",\"name\":\"Test %s\",\"slug\":\"%s\",\"ownerFirstName\":\"Owner\",\"phone\":\"%s\",\"password\":\"s3cretPass!\"%s}".formatted(type, slug, slug, nextPhone(), extraJson);
        return mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String text(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    @Test
    void listsAreComprehensiveInArabicAndEnglishWithOtherLast() throws Exception {
        for (String type : new String[] {"CLINIC", "STORE"}) {
            String res = text(mvc.perform(get("/api/v1/onboarding/categories").param("type", type).header("Host", "platform.test")).andExpect(status().isOk()));
            List<String> codes = JsonPath.read(res, "$[*].code");
            assertThat(codes).hasSizeGreaterThan(50).doesNotHaveDuplicates();
            assertThat(codes.get(codes.size() - 1)).isEqualTo("other");
            assertThat((List<String>) JsonPath.read(res, "$[*].nameAr")).doesNotContain("").allMatch(n -> n.length() > 1);
            assertThat((Boolean) JsonPath.read(res, "$[0].popular")).isTrue();   // most common first
        }
        String clinics = text(mvc.perform(get("/api/v1/onboarding/categories").param("type", "CLINIC").header("Host", "platform.test")));
        assertThat((List<String>) JsonPath.read(clinics, "$[*].nameAr")).contains("طب الأطفال", "الجلدية والتناسلية", "جراحة العظام", "الأنف والأذن والحنجرة", "طب وجراحة العيون", "التحاليل الطبية");
        String shops = text(mvc.perform(get("/api/v1/onboarding/categories").param("type", "STORE").header("Host", "platform.test")));
        assertThat((List<String>) JsonPath.read(shops, "$[*].nameAr")).contains("ملابس حريمي", "موبايلات وإكسسواراتها", "سوبر ماركت وبقالة");
    }

    @Test
    void clinicMustChooseASpecialtyOrOther() throws Exception {
        register("CLINIC", "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_REQUIRED"));
        register("CLINIC", ",\"categories\":[]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_REQUIRED"));
        register("CLINIC", ",\"categories\":[\"not_a_specialty\"]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_SPECIALTY"));
        register("CLINIC", ",\"categories\":[\"other\"]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTHER_CATEGORY_REQUIRED"));
        register("CLINIC", ",\"categories\":[\"pediatrics\",\"neonatology\",\"nephrology\",\"urology\",\"ent\",\"chest\"]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_CATEGORIES"));

        // a rejected sign-up leaves nothing behind: the same slug can be used right after
        String slug = "retry" + uniq();
        String bad = "{\"type\":\"CLINIC\",\"name\":\"R\",\"slug\":\"%s\",\"ownerFirstName\":\"O\",\"phone\":\"%s\",\"password\":\"s3cretPass!\",\"categories\":[\"nope\"]}".formatted(slug, nextPhone());
        mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/onboarding/slug-available").param("slug", slug).header("Host", "platform.test")).andExpect(jsonPath("$.available").value(true));

        // the doctor's specialty is on the owner's doctor profile and on the public site
        String ok = text(register("CLINIC", ",\"categories\":[\"pediatrics\",\"neonatology\"]").andExpect(status().isCreated()));
        String host = JsonPath.read(ok, "$.host");
        String access = JsonPath.read(ok, "$.tokens.accessToken");
        String doctors = text(onHost(host, access, "GET", "/api/v1/clinic/doctors", null).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(doctors, "$[0].specialties[*].code")).containsExactlyInAnyOrder("pediatrics", "neonatology");
        String site = text(onHost(host, null, "GET", "/api/v1/clinic/public/profile", null).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(site, "$.doctors[0].specialties[*].nameAr")).contains("طب الأطفال");
    }

    @Test
    void otherSpecialtyKeepsTheTypedName() throws Exception {
        String ok = text(register("CLINIC", ",\"categories\":[\"other\"],\"otherCategory\":\"  طب الحالات النادرة \"").andExpect(status().isCreated()));
        String host = JsonPath.read(ok, "$.host");
        String access = JsonPath.read(ok, "$.tokens.accessToken");
        onHost(host, access, "GET", "/api/v1/clinic/doctors", null).andExpect(jsonPath("$[0].otherSpecialty").value("طب الحالات النادرة")).andExpect(jsonPath("$[0].specialties[0].code").value("other"));
        // switching to a real specialty drops the typed name
        String me = text(onHost(host, access, "PATCH", "/api/v1/clinic/doctors/me", "{\"specialties\":[\"dermatology\"]}").andExpect(status().isOk()));
        assertThat((Object) JsonPath.read(me, "$.otherSpecialty")).isNull();
        // "other" again without a name is refused
        onHost(host, access, "PATCH", "/api/v1/clinic/doctors/me", "{\"specialties\":[\"other\"]}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTHER_CATEGORY_REQUIRED"));
        onHost(host, access, "PATCH", "/api/v1/clinic/doctors/me", "{\"fields\":{\"otherSpecialty\":\"Voice therapy\"},\"specialties\":[\"dermatology\",\"other\"]}").andExpect(status().isOk()).andExpect(jsonPath("$.otherSpecialty").value("Voice therapy"));
    }

    @Test
    void shopMustChooseACategoryOrOtherAndCanChangeItLater() throws Exception {
        register("STORE", "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_REQUIRED"));
        register("STORE", ",\"categories\":[\"pediatrics\"]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_CATEGORY"));   // a clinic specialty is not a shop category
        register("STORE", ",\"categories\":[\"other\"]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTHER_CATEGORY_REQUIRED"));

        String ok = text(register("STORE", ",\"categories\":[\"toys\",\"baby_care\"]").andExpect(status().isCreated()));
        String host = JsonPath.read(ok, "$.host");
        String access = JsonPath.read(ok, "$.tokens.accessToken");
        String profile = text(onHost(host, access, "GET", "/api/v1/store/profile", null).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(profile, "$.categories[*].code")).containsExactlyInAnyOrder("toys", "baby_care");

        onHost(host, access, "PUT", "/api/v1/store/profile/categories", "{\"categories\":[\"other\"],\"otherCategory\":\"Hand-made soap\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.otherCategory").value("Hand-made soap")).andExpect(jsonPath("$.categories[0].code").value("other"));
        onHost(host, access, "PUT", "/api/v1/store/profile/categories", "{\"categories\":[\"perfumes\"]}").andExpect(status().isOk()).andExpect(jsonPath("$.otherCategory").doesNotExist());
        onHost(host, access, "PUT", "/api/v1/store/profile/categories", "{\"categories\":[]}").andExpect(status().isBadRequest());
        String list = text(onHost(host, access, "GET", "/api/v1/store/business-categories", null).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(list, "$[*].code")).contains("perfumes", "other");
        // the public shop profile carries the categories too
        String shop = text(onHost(host, null, "GET", "/api/v1/shop/profile", null).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(shop, "$.profile.categories[*].code")).containsExactly("perfumes");
    }
}
