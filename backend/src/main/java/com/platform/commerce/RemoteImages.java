package com.platform.commerce;

import java.util.Optional;

/** Downloads a picture the owner's own Facebook page or Instagram account published, so the shop keeps its own copy (Meta's picture links expire). One seam so tests never use the network. */
public interface RemoteImages {
    record Image(byte[] data, String contentType) {}

    /** The picture, or empty when the address is not an allowed Meta picture host, is too big, is not an image, or cannot be fetched. Never throws. */
    Optional<Image> fetch(String url);
}
