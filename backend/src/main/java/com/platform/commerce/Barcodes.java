package com.platform.commerce;

/**
 * EAN-13 barcodes. A shop's own products without a manufacturer barcode get an internal one in the 20… range, which GS1 keeps for in-store use, so it never clashes with a
 * real product's code: "20", ten digits counting up per shop, and the check digit.
 */
public final class Barcodes {
    private Barcodes() {}

    /** The check digit of the first 12 digits of an EAN-13. */
    public static int ean13Check(String twelve) {
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (twelve.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return (10 - sum % 10) % 10;
    }

    /** The internal barcode number {@code n} of a shop: 20 + n on ten digits + the check digit. */
    public static String internal(long n) {
        String twelve = "20" + String.format("%010d", n);
        return twelve + ean13Check(twelve);
    }

    public static boolean isEan13(String code) {
        return code != null && code.matches("\\d{13}") && ean13Check(code.substring(0, 12)) == code.charAt(12) - '0';
    }
}
