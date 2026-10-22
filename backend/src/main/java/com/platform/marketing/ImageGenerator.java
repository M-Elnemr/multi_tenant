package com.platform.marketing;

/** Makes marketing pictures with AI. One seam so tests never call the network. */
public interface ImageGenerator {
    record Generated(byte[] data, String contentType) {}

    boolean available();

    /** {@code reference} (the product's own photo, may be null) keeps the generated picture true to the real product. Throws {@link ImageException} when nothing was made. */
    Generated generate(String prompt, byte[] reference, String referenceType);

    class ImageException extends RuntimeException {
        public ImageException(String message) { super(message); }
        public ImageException(String message, Throwable cause) { super(message, cause); }
    }
}
