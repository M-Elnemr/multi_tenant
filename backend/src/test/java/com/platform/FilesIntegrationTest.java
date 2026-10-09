package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class FilesIntegrationTest extends IntegrationTestBase {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext ctx;

    static final byte[] PNG = realPng();
    static final byte[] PDF = bytes("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII), 64);

    static byte[] realPng() {
        try { return ImageProcessorTest.png(600, 400, false); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static byte[] bytes(byte[] head, int total) {
        byte[] b = Arrays.copyOf(head, total);
        for (int i = head.length; i < total; i++) b[i] = (byte) i;
        return b;
    }

    String presign(Tenant t, String token, String name, String type, long size, String category, String visibility, int expect) throws Exception {
        String vis = visibility == null ? "" : ",\"visibility\":\"" + visibility + "\"";
        return onHost(t.host(), token, "POST", "/api/v1/files/presign", "{\"filename\":\"%s\",\"contentType\":\"%s\",\"size\":%d,\"category\":\"%s\"%s}".formatted(name, type, size, category, vis))
                .andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
    }

    ResultActions putFile(Tenant t, String uploadUrl, String type, byte[] data) throws Exception {
        return mvc.perform(put(uploadUrl).header("Host", t.host()).contentType(MediaType.parseMediaType(type)).content(data));
    }

    ResultActions getFile(String host, String token, String fileId) throws Exception {
        var b = get("/api/v1/files/" + fileId + "/content").header("Host", host);
        if (token != null) b.header("Authorization", "Bearer " + token);
        return mvc.perform(b);
    }

    @Test
    void publicProductImageRoundTripAndUploadValidation() throws Exception {
        Tenant store = onboard("STORE");
        String res = presign(store, store.access(), "shirt.png", "image/png", PNG.length, "PRODUCT_IMAGE", null, 200);
        String id = JsonPath.read(res, "$.fileId");
        String url = JsonPath.read(res, "$.uploadUrl");

        // nothing is served until the upload completes
        getFile(store.host(), null, id).andExpect(status().isNotFound());
        // wrong magic bytes / wrong content type / forged signature are rejected
        putFile(store, url, "image/png", PDF).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_NOT_ALLOWED"));
        putFile(store, url, "application/pdf", PNG).andExpect(status().isBadRequest());
        putFile(store, url.replaceAll("sig=.*", "sig=deadbeef"), "image/png", PNG).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UPLOAD_EXPIRED"));
        putFile(store, url, "image/png", PNG).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        onHost(store.host(), store.access(), "GET", "/api/v1/files/usage", null).andExpect(jsonPath("$.usedBytes").isNumber()).andExpect(jsonPath("$.limitBytes").value(10240L * 1024 * 1024));
        putFile(store, url, "image/png", PNG).andExpect(status().isConflict());   // single use

        // product images are public: anyone on the store host can load them, with safe headers
        getFile(store.host(), null, id).andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg")).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        // pictures are re-encoded (EXIF stripped, bounded size), so the served bytes are a valid PNG of the same picture, not the raw upload
        var served = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(getFile(store.host(), null, id).andReturn().getResponse().getContentAsByteArray()));
        assertThat(served.getWidth()).isEqualTo(600);   // opaque PNGs are stored as JPEG (smaller); transparent ones stay PNG
        var thumb = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(mvc.perform(get("/api/v1/files/" + id + "/content?variant=thumb").header("Host", store.host())).andReturn().getResponse().getContentAsByteArray()));
        assertThat(thumb.getWidth()).isEqualTo(320);
        String etag = getFile(store.host(), null, id).andReturn().getResponse().getHeader("ETag");
        assertThat(etag).isNotNull();
        mvc.perform(get("/api/v1/files/" + id + "/content").header("Host", store.host()).header("If-None-Match", etag)).andExpect(status().isNotModified());
        getFile(store.host(), null, id).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));

        // attach to a product
        String product = onHost(store.host(), store.access(), "POST", "/api/v1/store/products",
                "{\"name\":\"Tee %s\",\"variants\":[{\"sku\":\"T-%s\",\"priceMinor\":100,\"optionValues\":{}}],\"media\":[{\"fileId\":\"%s\",\"altText\":\"front\"}]}".formatted(uniq(), uniq(), id))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.media[0].url").value("/api/v1/files/" + id + "/content")).andReturn().getResponse().getContentAsString();
        assertThat(product).contains(id);

        // another tenant cannot read it through its own host, nor attach it to its products
        Tenant other = onboard("STORE");
        getFile(other.host(), null, id).andExpect(status().isNotFound());
        onHost(other.host(), other.access(), "POST", "/api/v1/store/products",
                "{\"name\":\"Steal %s\",\"variants\":[{\"sku\":\"S-%s\",\"priceMinor\":1,\"optionValues\":{}}],\"media\":[{\"fileId\":\"%s\"}]}".formatted(uniq(), uniq(), id)).andExpect(status().isBadRequest());

        // policy checks at presign time
        presign(store, store.access(), "x.exe", "application/x-msdownload", 100, "PRODUCT_IMAGE", null, 400);
        presign(store, store.access(), "big.png", "image/png", 6L * 1024 * 1024, "PRODUCT_IMAGE", null, 400);
        presign(store, store.access(), "doc.pdf", "application/pdf", 100, "PRODUCT_IMAGE", null, 400);
        presign(store, store.access(), "p.webp", "image/webp", 100, "PRODUCT_IMAGE", null, 400);   // only formats we can fully re-encode
        presign(store, null, "a.png", "image/png", 100, "PRODUCT_IMAGE", null, 401);
    }

    @Test
    void medicalFilesArePrivateAndOnlyReachTheirPatientAndAuthorizedStaff() throws Exception {
        Tenant clinic = onboard("CLINIC");
        presign(clinic, clinic.access(), "scan.pdf", "application/pdf", 100, "LAB_RESULT", "PUBLIC", 400);   // medical can never be public

        // patient accounts via the clinic flow
        PatientLogin nour = patientAt(clinic, "Nour");
        String patientId = nour.patientId();
        String patientToken = nour.token();
        String otherToken = patientAt(clinic, "Other").token();

        // visit + lab order, then the patient uploads a result PDF
        String enc = JsonPath.read(onHost(clinic.host(), clinic.access(), "POST", "/api/v1/clinic/encounters", "{\"patientId\":\"%s\"}".formatted(patientId)).andReturn().getResponse().getContentAsString(), "$.id");
        String lab = JsonPath.read(onHost(clinic.host(), clinic.access(), "POST", "/api/v1/clinic/encounters/" + enc + "/lab-orders", "{\"testName\":\"CBC\"}").andReturn().getResponse().getContentAsString(), "$.id");
        String res = presign(clinic, patientToken, "cbc.pdf", "application/pdf", PDF.length, "LAB_RESULT", null, 200);
        String fileId = JsonPath.read(res, "$.fileId");
        putFile(clinic, JsonPath.read(res, "$.uploadUrl"), "application/pdf", PDF).andExpect(status().isOk());

        // another patient cannot attach someone else's file to their own lab order; the owner can
        onHost(clinic.host(), otherToken, "POST", "/api/v1/portal/lab-orders/" + lab + "/results", "{\"fileId\":\"%s\"}".formatted(fileId)).andExpect(status().isNotFound());
        onHost(clinic.host(), patientToken, "POST", "/api/v1/portal/lab-orders/" + lab + "/results", "{\"fileId\":\"%s\"}".formatted(fileId)).andExpect(status().isCreated());

        // access matrix
        getFile(clinic.host(), null, fileId).andExpect(status().isNotFound());                 // anonymous
        getFile(clinic.host(), otherToken, fileId).andExpect(status().isNotFound());            // another patient
        getFile(clinic.host(), patientToken, fileId).andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        getFile(clinic.host(), clinic.access(), fileId).andExpect(status().isOk());             // the doctor
        // reception has patient.read but no clinical permission
        String rPhone = nextPhone();
        String inv = onHost(clinic.host(), clinic.access(), "POST", "/api/v1/tenant/members", "{\"firstName\":\"R\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(rPhone)).andReturn().getResponse().getContentAsString();
        String rec = JsonPath.read(onHost(clinic.host(), null, "POST", "/api/v1/auth/activate", "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"ReceptionPass1\"}".formatted(rPhone, JsonPath.read(inv, "$.activationPin").toString())).andReturn().getResponse().getContentAsString(), "$.accessToken");
        getFile(clinic.host(), rec, fileId).andExpect(status().isNotFound());
        // other clinic
        Tenant other = onboard("CLINIC");
        getFile(other.host(), other.access(), fileId).andExpect(status().isNotFound());
        assertThat(List.of(fileId)).isNotEmpty();
    }

    @Test
    void filesAreDeletableOnlyWhenUnusedAndQuotaIsEnforcedAndFreed() throws Exception {
        Tenant store = onboard("STORE");
        String res = presign(store, store.access(), "a.png", "image/png", PNG.length, "PRODUCT_IMAGE", null, 200);
        String id = JsonPath.read(res, "$.fileId");
        putFile(store, JsonPath.read(res, "$.uploadUrl"), "image/png", PNG).andExpect(status().isOk());
        long used = ((Number) JsonPath.read(onHost(store.host(), store.access(), "GET", "/api/v1/files/usage", null).andReturn().getResponse().getContentAsString(), "$.usedBytes")).longValue();
        assertThat(used).isGreaterThan(0);

        // in use -> refused; free -> deleted, quota released, content gone
        onHost(store.host(), store.access(), "POST", "/api/v1/store/products", "{\"name\":\"Cup %s\",\"variants\":[{\"sku\":\"C-%s\",\"priceMinor\":1,\"optionValues\":{}}],\"media\":[{\"fileId\":\"%s\"}]}".formatted(uniq(), uniq(), id)).andExpect(status().isCreated());
        onHost(store.host(), store.access(), "DELETE", "/api/v1/files/" + id, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_IN_USE"));
        String res2 = presign(store, store.access(), "b.png", "image/png", PNG.length, "PRODUCT_IMAGE", null, 200);
        String id2 = JsonPath.read(res2, "$.fileId");
        putFile(store, JsonPath.read(res2, "$.uploadUrl"), "image/png", PNG).andExpect(status().isOk());
        Tenant other = onboard("STORE");
        onHost(other.host(), other.access(), "DELETE", "/api/v1/files/" + id2, null).andExpect(status().isNotFound());   // another tenant cannot delete it
        onHost(store.host(), store.access(), "DELETE", "/api/v1/files/" + id2, null).andExpect(status().isNoContent());
        getFile(store.host(), null, id2).andExpect(status().isNotFound());
        long after = ((Number) JsonPath.read(onHost(store.host(), store.access(), "GET", "/api/v1/files/usage", null).andReturn().getResponse().getContentAsString(), "$.usedBytes")).longValue();
        assertThat(after).isEqualTo(used);

        // plan quota: when the allowance is used up, new uploads are refused with a stable code
        org.springframework.jdbc.core.simple.JdbcClient jdbc = ctx.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
        jdbc.sql("UPDATE billing.usage_counters SET value = :v WHERE metric = 'storage_bytes' AND tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("v", 10240L * 1024 * 1024 - 10).param("s", store.slug()).update();
        presign(store, store.access(), "c.png", "image/png", PNG.length, "PRODUCT_IMAGE", null, 403);
    }
}
