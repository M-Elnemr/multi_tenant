package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A product picture can belong to one colour; the product page reads the colour of each picture. */
class ColorMediaIntegrationTest extends IntegrationTestBase {
    @Test
    void picturesCanBeTaggedWithAColourAndUntagged() throws Exception {
        Tenant t = onboard("STORE");
        String body = """
                {"taxonomySlug":"hats-caps","audience":"ALL","name":"Cap %1$s","options":[{"name":"Color","values":["Red","Blue"]}],
                 "variants":[{"sku":"CAP-R-%1$s","priceMinor":100,"optionValues":{"Color":"Red"}},{"sku":"CAP-B-%1$s","priceMinor":100,"optionValues":{"Color":"Blue"}}],
                 "media":[{"url":"https://img.test/general.jpg"},{"url":"https://img.test/typed-red.jpg","optionValue":"Red"}]}
                """.formatted(uniq());
        String created = onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        List<String> values = JsonPath.read(created, "$.options[0].values");   // stored in the shop's standard names
        String red = values.get(0);
        assertThat((Object) JsonPath.read(created, "$.media[0].optionValue")).isNull();
        assertThat((String) JsonPath.read(created, "$.media[1].optionValue")).isEqualTo(red);   // typed as "Red", stored under the standard name

        // a picture uploaded for a colour
        String withRed = onHost(t.host(), t.access(), "POST", "/api/v1/store/products/" + id + "/media", "{\"url\":\"https://img.test/red.jpg\",\"optionValue\":\"" + red + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(withRed, "$.media[2].optionValue")).isEqualTo(red);
        // the public product page carries it too
        String slug = JsonPath.read(withRed, "$.slug");
        onHost(t.host(), null, "GET", "/api/v1/shop/products/" + slug, null).andExpect(status().isOk()).andExpect(jsonPath("$.media[2].optionValue").value(red));

        // a colour the product does not have is refused
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products/" + id + "/media", "{\"url\":\"https://img.test/x.jpg\",\"optionValue\":\"Purple\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_OPTION_VALUE"));

        // re-tag the general picture to the other colour, then back to general
        String firstId = JsonPath.read(withRed, "$.media[0].id");
        String other = values.get(1);
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/products/" + id + "/media/" + firstId + "/color", "{\"value\":\"" + other + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.media[0].optionValue").value(other));
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/products/" + id + "/media/" + firstId + "/color", "{\"value\":\"\"}").andExpect(status().isOk()).andExpect(jsonPath("$.media[0].optionValue").doesNotExist());
    }
}
