package com.platform.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development / single-box storage on local disk (one folder per bucket). Production uses S3FileStorage. */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {
    private final Path root;

    public LocalFileStorage(@Value("${app.files.dir:./uploads}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /** Keys are always server-generated, but resolve defensively so a bad key can never escape the storage root. */
    private Path resolve(Bucket b, String key) {
        Path base = root.resolve(b.name().toLowerCase());
        Path p = base.resolve(key).normalize();
        if (!p.startsWith(base)) throw new IllegalArgumentException("Invalid storage key");
        return p;
    }

    @Override
    public void put(Bucket b, String key, byte[] data, String contentType, String cacheControl) throws IOException {
        Path p = resolve(b, key);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
    }

    @Override
    public byte[] get(Bucket b, String key) throws IOException { return Files.readAllBytes(resolve(b, key)); }

    @Override
    public void delete(Bucket b, String key) throws IOException { Files.deleteIfExists(resolve(b, key)); }
}
