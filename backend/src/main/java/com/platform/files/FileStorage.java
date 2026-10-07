package com.platform.files;

import java.io.IOException;

/**
 * Object-storage abstraction. Two logical buckets:
 *  PUBLIC  - processed product images/logos with immutable keys, readable by anyone through the edge proxy (/media/...);
 *  PRIVATE - medical files and everything else: never reachable without going through an authorized API call.
 */
public interface FileStorage {
    enum Bucket { PUBLIC, PRIVATE }

    void put(Bucket bucket, String key, byte[] data, String contentType, String cacheControl) throws IOException;

    byte[] get(Bucket bucket, String key) throws IOException;

    /** Deleting a missing object is not an error. */
    void delete(Bucket bucket, String key) throws IOException;

    /** Browser-reachable path of a PUBLIC object (served by the edge proxy straight from the bucket), or null if the backend must stream it. */
    default String publicPath(String key) { return null; }
}
