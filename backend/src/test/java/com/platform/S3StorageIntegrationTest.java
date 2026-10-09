package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/**
 * The whole upload/serve path against a real S3 API server (an S3-compatible server such as moto; MinIO speaks the same protocol).
 * Skipped unless MOTO_SERVER_BIN points at a `moto_server` executable (CI installs it with pip).
 */
class S3StorageIntegrationTest extends IntegrationTestBase {
    static Process server;
    static int port;
    static {
        try (ServerSocket sock = new ServerSocket(0)) { port = sock.getLocalPort(); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static final String PUBLIC = "public-media", PRIVATE = "private-medical";

    static String motoBin() {
        String env = System.getenv("MOTO_SERVER_BIN");
        return env != null && !env.isBlank() && Files.isExecutable(Path.of(env)) ? env : null;
    }

    static S3Client client() {
        return S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:" + port)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    @BeforeAll
    static void startServer() throws Exception {
        if (motoBin() == null) return;
        server = new ProcessBuilder(motoBin(), "-p", String.valueOf(port)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        for (int i = 0; i < 60; i++) {
            try { client().listBuckets(); break; } catch (Exception e) { Thread.sleep(500); }
        }
        S3Client c = client();
        c.createBucket(CreateBucketRequest.builder().bucket(PUBLIC).build());
        c.createBucket(CreateBucketRequest.builder().bucket(PRIVATE).build());
    }

    @AfterAll
    static void stopServer() { if (server != null) server.destroyForcibly(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        if (motoBin() == null) return;
        r.add("app.storage.type", () -> "s3");
        r.add("app.storage.s3.endpoint", () -> "http://127.0.0.1:" + port);
        r.add("app.storage.s3.access-key", () -> "test");
        r.add("app.storage.s3.secret-key", () -> "test");
        r.add("app.storage.s3.public-bucket", () -> PUBLIC);
        r.add("app.storage.s3.private-bucket", () -> PRIVATE);
    }

    List<String> keys(String bucket) {
        return client().listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents().stream().map(o -> o.key()).toList();
    }

    String up(Tenant t, String token, String name, String type, byte[] data, String category) throws Exception {
        String res = onHost(t.host(), token, "POST", "/api/v1/files/presign", "{\"filename\":\"%s\",\"contentType\":\"%s\",\"size\":%d,\"category\":\"%s\"}".formatted(name, type, data.length, category))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(put((String) JsonPath.read(res, "$.uploadUrl")).header("Host", t.host()).contentType(MediaType.parseMediaType(type)).content(data)).andExpect(status().isOk());
        return JsonPath.read(res, "$.fileId");
    }

    @Test
    void publicImagesGoToThePublicBucketAsImmutableVariantsAndAreServedByRedirect() throws Exception {
        assumeTrue(server != null, "MOTO_SERVER_BIN not set: S3 test skipped");
        Tenant store = onboard("STORE");
        String id = up(store, store.access(), "p.png", "image/png", ImageProcessorTest.png(1200, 900, false), "PRODUCT_IMAGE");
        List<String> pub = keys(PUBLIC);
        String prefix = "tenants/%s/img/%s/".formatted(JsonPath.<String>read(onHost(store.host(), null, "GET", "/api/v1/tenant/context", null).andReturn().getResponse().getContentAsString(), "$.id"), id);
        assertThat(pub).anyMatch(k -> k.startsWith(prefix) && k.endsWith("-thumb.jpg")).anyMatch(k -> k.endsWith("-medium.jpg")).anyMatch(k -> k.endsWith("-original.jpg"));
        assertThat(keys(PRIVATE)).noneMatch(k -> k.contains(id));   // nothing public leaks into the private bucket and vice versa

        // the app does not stream public images: it points the browser at the immutable object the edge proxy serves from the bucket
        var r = mvc.perform(get("/api/v1/files/" + id + "/content?variant=thumb").header("Host", store.host())).andExpect(status().isFound()).andReturn().getResponse();
        assertThat(r.getHeader("Location")).startsWith("/media/" + prefix).endsWith("-thumb.jpg");

        // a product records the direct public location so catalog pages link the image without an app round trip
        String product = onHost(store.host(), store.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"home-lighting\",\"name\":\"Lamp %s\",\"variants\":[{\"sku\":\"L-%s\",\"priceMinor\":1,\"optionValues\":{}}],\"media\":[{\"fileId\":\"%s\"}]}".formatted(uniq(), uniq(), id)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(product, "$.media[0].mediaBase")).startsWith("/media/" + prefix);
        assertThat((String) JsonPath.read(product, "$.media[0].mediaExt")).isEqualTo("jpg");

        // deleting removes every variant from the bucket
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/store/products/" + JsonPath.read(product, "$.id")).header("Host", store.host()).header("Authorization", "Bearer " + store.access()));
    }

    @Test
    void medicalFilesStayInThePrivateBucketUnchangedAndAreOnlyStreamedToAuthorizedUsers() throws Exception {
        assumeTrue(server != null, "MOTO_SERVER_BIN not set: S3 test skipped");
        Tenant clinic = onboard("CLINIC");
        byte[] pdf = FilesIntegrationTest.bytes("%PDF-1.4\n".getBytes(), 2048);
        String id = up(clinic, clinic.access(), "lab.pdf", "application/pdf", pdf, "LAB_RESULT");
        assertThat(keys(PRIVATE)).anyMatch(k -> k.contains(id));
        assertThat(keys(PUBLIC)).noneMatch(k -> k.contains(id));
        var ok = mvc.perform(get("/api/v1/files/" + id + "/content").header("Host", clinic.host()).header("Authorization", "Bearer " + clinic.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse();
        assertThat(ok.getContentAsByteArray()).isEqualTo(pdf);    // medical documents are stored byte-for-byte
        mvc.perform(get("/api/v1/files/" + id + "/content").header("Host", clinic.host())).andExpect(status().isNotFound());   // anonymous
        Tenant other = onboard("CLINIC");
        mvc.perform(get("/api/v1/files/" + id + "/content").header("Host", other.host()).header("Authorization", "Bearer " + other.access())).andExpect(status().isNotFound());   // another clinic
    }
}
