/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import ai.labs.eddi.engine.attachments.blob.BlobLengthException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("LengthCheckedInputStream")
class LengthCheckedInputStreamTest {

    private static InputStream bytes(int n) {
        return new ByteArrayInputStream(new byte[n]);
    }

    @Test
    void passesAStreamOfExactlyTheDeclaredLength() throws Exception {
        LengthCheckedInputStream in = new LengthCheckedInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, 3);

        assertArrayEquals(new byte[]{1, 2, 3}, in.readAllBytes());
        assertEquals(3, in.bytesRead());
    }

    @Test
    void passesAStreamOfUnknownLengthUpToMaxBytes() throws Exception {
        assertEquals(10, new LengthCheckedInputStream(bytes(10), -1, 10).readAllBytes().length);
    }

    @Test
    void refusesOneByteBeyondTheDeclaredLength() {
        LengthCheckedInputStream in = assertDoesNotThrowOnConstruction(bytes(11), 10, 100);

        assertEquals(Reason.EXCEEDS_DECLARED_LENGTH, assertThrows(BlobLengthException.class, in::readAllBytes).reason());
    }

    @Test
    void refusesOneByteBeyondMaxBytes() {
        LengthCheckedInputStream in = assertDoesNotThrowOnConstruction(bytes(11), -1, 10);

        assertEquals(Reason.EXCEEDS_MAX_BYTES, assertThrows(BlobLengthException.class, in::readAllBytes).reason());
    }

    @Test
    void countsSingleByteReadsToo() throws Exception {
        LengthCheckedInputStream in = new LengthCheckedInputStream(bytes(3), 2, 10);
        in.read();
        in.read();

        assertEquals(Reason.EXCEEDS_DECLARED_LENGTH, assertThrows(BlobLengthException.class, in::read).reason());
    }

    @Test
    void countsSkippedBytes() throws Exception {
        LengthCheckedInputStream in = new LengthCheckedInputStream(bytes(20), -1, 10);

        assertEquals(Reason.EXCEEDS_MAX_BYTES, assertThrows(BlobLengthException.class, () -> in.skip(15)).reason());
    }

    @Test
    void refusesAStreamThatEndsBeforeTheDeclaredLength() {
        LengthCheckedInputStream in = assertDoesNotThrowOnConstruction(bytes(9), 10, 100);

        assertEquals(Reason.SHORTER_THAN_DECLARED, assertThrows(BlobLengthException.class, in::readAllBytes).reason());
    }

    @Test
    void refusesADeclaredLengthAboveMaxBytesOnConstruction() {
        assertEquals(Reason.EXCEEDS_MAX_BYTES,
                assertThrows(BlobLengthException.class, () -> new LengthCheckedInputStream(bytes(0), 11, 10)).reason());
    }

    @Test
    void refusesANonPositiveMaxBytes() {
        assertThrows(IllegalArgumentException.class, () -> new LengthCheckedInputStream(bytes(0), -1, 0));
    }

    private static LengthCheckedInputStream assertDoesNotThrowOnConstruction(InputStream in, long declared, long max) {
        try {
            return new LengthCheckedInputStream(in, declared, max);
        } catch (BlobLengthException e) {
            throw new AssertionError(e);
        }
    }
}
