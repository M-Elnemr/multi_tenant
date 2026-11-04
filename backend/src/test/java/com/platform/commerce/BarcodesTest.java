package com.platform.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BarcodesTest {
    @Test
    void internalCodesAreValidEan13InTheInStoreRange() {
        assertThat(Barcodes.internal(1)).isEqualTo("2000000000015");
        assertThat(Barcodes.isEan13(Barcodes.internal(1))).isTrue();
        assertThat(Barcodes.isEan13("6221234567890")).isFalse();   // wrong check digit
        assertThat(Barcodes.isEan13("4006381333931")).isTrue();    // a real EAN-13
        assertThat(Barcodes.internal(1234567890L)).startsWith("201234567890");
    }
}
