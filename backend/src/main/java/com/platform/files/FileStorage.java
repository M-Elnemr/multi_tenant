package com.platform.files;

import java.io.IOException;

/** Object-storage abstraction. The local-disk implementation is for single-VPS/dev; an S3/MinIO implementation plugs in here. */
public interface FileStorage {
    void put(String key, byte[] data) throws IOException;

    byte[] get(String key) throws IOException;

    void delete(String key) throws IOException;
}
