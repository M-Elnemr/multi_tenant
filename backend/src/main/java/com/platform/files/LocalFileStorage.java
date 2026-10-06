package com.platform.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalFileStorage implements FileStorage {
    private final Path root;

    public LocalFileStorage(@Value("${app.files.dir:./uploads}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /** Keys are always server-generated, but resolve defensively so a bad key can never escape the storage root. */
    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return p;
    }

    @Override
    public void put(String key, byte[] data) throws IOException {
        Path p = resolve(key);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
    }

    @Override
    public byte[] get(String key) throws IOException { return Files.readAllBytes(resolve(key)); }

    @Override
    public void delete(String key) throws IOException { Files.deleteIfExists(resolve(key)); }
}
