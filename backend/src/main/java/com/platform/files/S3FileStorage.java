package com.platform.files;

import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Any S3-compatible store (MinIO, Garage, Cloudflare R2, AWS S3): endpoint, credentials and bucket names come from configuration. */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3FileStorage implements FileStorage {
    private final S3Client s3;
    private final String publicBucket;
    private final String privateBucket;
    private final String publicPrefix;

    public S3FileStorage(@Value("${app.storage.s3.endpoint}") String endpoint, @Value("${app.storage.s3.region:us-east-1}") String region,
                         @Value("${app.storage.s3.access-key}") String accessKey, @Value("${app.storage.s3.secret-key}") String secretKey,
                         @Value("${app.storage.s3.public-bucket:public-media}") String publicBucket,
                         @Value("${app.storage.s3.private-bucket:private-medical}") String privateBucket,
                         @Value("${app.storage.public-path:/media}") String publicPrefix) {
        this.s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        this.publicBucket = publicBucket;
        this.privateBucket = privateBucket;
        this.publicPrefix = publicPrefix.endsWith("/") ? publicPrefix.substring(0, publicPrefix.length() - 1) : publicPrefix;
    }

    private String bucket(Bucket b) { return b == Bucket.PUBLIC ? publicBucket : privateBucket; }

    @Override
    public void put(Bucket b, String key, byte[] data, String contentType, String cacheControl) throws IOException {
        try {
            PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket(b)).key(key).contentType(contentType);
            if (cacheControl != null) req.cacheControl(cacheControl);
            s3.putObject(req.build(), RequestBody.fromBytes(data));
        } catch (S3Exception e) {
            throw new IOException("Storage write failed: " + e.awsErrorDetails().errorMessage(), e);
        } catch (SdkException e) {
            throw new IOException("Storage unreachable", e);
        }
    }

    @Override
    public byte[] get(Bucket b, String key) throws IOException {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket(b)).key(key).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new IOException("Object not found", e);
        } catch (S3Exception e) {
            throw new IOException("Storage read failed: " + e.awsErrorDetails().errorMessage(), e);
        } catch (SdkException e) {
            throw new IOException("Storage unreachable", e);
        }
    }

    @Override
    public void delete(Bucket b, String key) throws IOException {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket(b)).key(key).build());
        } catch (S3Exception e) {
            throw new IOException("Storage delete failed: " + e.awsErrorDetails().errorMessage(), e);
        } catch (SdkException e) {
            throw new IOException("Storage unreachable", e);
        }
    }

    /** The edge proxy maps {publicPrefix}/... to the public bucket, so the browser reads images without touching the app. */
    @Override
    public String publicPath(String key) { return publicPrefix + "/" + key; }
}
