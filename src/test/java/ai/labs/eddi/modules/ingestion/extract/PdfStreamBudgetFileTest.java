/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * The file variant of the pre-scan decides exactly as the array variant does —
 * with windows so small that keywords, dictionaries and streams straddle them.
 */
@DisplayName("PdfStreamBudgetFile — the pre-scan on a file")
class PdfStreamBudgetFileTest {

    private static final long ONE_MEGABYTE = 1024 * 1024;

    @TempDir
    Path dir;

    static Stream<Arguments> documents() {
        return Stream.of(
                Arguments.of("plain text pages", PdfFixtures.plain(3)),
                Arguments.of("outlined chapters", PdfFixtures.outlined(3, 2)),
                Arguments.of("a Flate bomb page", PdfFixtures.flateBomb(8 * 1024 * 1024)),
                Arguments.of("a bomb behind a 'stream' in a dictionary string", streamWordInDictionary()),
                Arguments.of("a bomb whose data starts after spaces", spacedKeyword()),
                Arguments.of("a large image, passed over", largeImage()),
                Arguments.of("an unterminated bomb", unterminated()),
                Arguments.of("a bomb behind a long dictionary", longDictionary()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documents")
    @DisplayName("refuses what the array scan refuses, passes what it passes, at every window size")
    void sameDecisionAsTheArrayScan(String name, byte[] pdf) throws IOException {
        Path file = Files.write(dir.resolve("doc.pdf"), pdf);
        boolean arrayRefuses = refuses(() -> PdfStreamBudget.requireWithin(pdf, ONE_MEGABYTE, later()));
        for (int window : new int[]{16, 17, 64, 4096, PdfStreamBudgetFile.DEFAULT_WINDOW_BYTES}) {
            boolean fileRefuses = refuses(() -> PdfStreamBudgetFile.requireWithin(file, ONE_MEGABYTE, later(), window));
            assertEquals(arrayRefuses, fileRefuses, name + " with a window of " + window + " bytes");
        }
    }

    @Test
    @DisplayName("the cases above cover both outcomes")
    void bothOutcomesCovered() {
        long refused = documents().filter(arguments -> refuses(() -> PdfStreamBudget
                .requireWithin((byte[]) arguments.get()[1], ONE_MEGABYTE, later()))).count();
        assertEquals(5, refused, "five bombs, three harmless files");
    }

    @Test
    @DisplayName("a bomb is refused with the pre-scan's message")
    void refusesWithMessage() throws IOException {
        Path file = Files.write(dir.resolve("bomb.pdf"), PdfFixtures.flateBomb(8 * 1024 * 1024));

        var refused = assertThrows(UnreadableDocumentException.class,
                () -> PdfStreamBudgetFile.requireWithin(file, ONE_MEGABYTE, later()));
        assertEquals(true, refused.getMessage().contains("compressed stream that expands"), refused.getMessage());
    }

    @Test
    @DisplayName("a missing file is unreadable, not an I/O error")
    void missingFile() {
        assertThrows(UnreadableDocumentException.class,
                () -> PdfStreamBudgetFile.requireWithin(dir.resolve("absent.pdf"), ONE_MEGABYTE, later()));
    }

    @Test
    @DisplayName("the deadline applies")
    void deadline() throws IOException {
        Path file = Files.write(dir.resolve("doc.pdf"), PdfFixtures.flateBomb(1024));

        var refused = assertThrows(UnreadableDocumentException.class,
                () -> PdfStreamBudgetFile.requireWithin(file, ONE_MEGABYTE, Instant.now().minusSeconds(1)));
        assertEquals(true, refused.getMessage().contains("too long"), refused.getMessage());
    }

    @Test
    @DisplayName("a backward search through a long stretch does not refill the window per byte")
    void backwardSearchIsLinear() throws IOException {
        // Two objects with 4 MB of unfiltered data between their keywords: finding the
        // second one's "obj" searches backwards through all of it.
        var out = new ByteArrayOutputStream();
        out.writeBytes(ascii("1 0 obj\n<</Length 4194304>>stream\n"));
        byte[] filler = new byte[4 * 1024 * 1024];
        Arrays.fill(filler, (byte) 'x');
        out.writeBytes(filler);
        out.writeBytes(ascii("\nendstream\nendobj\n2 0 obj\n<</Length 3>>stream\nabc\nendstream\nendobj\n"));
        Path file = Files.write(dir.resolve("long.pdf"), out.toByteArray());

        assertTimeout(Duration.ofSeconds(10),
                () -> PdfStreamBudgetFile.requireWithin(file, ONE_MEGABYTE, later(), 256));
    }

    // --- inputs

    private static byte[] streamWordInDictionary() {
        byte[] bomb = zlib(new byte[4 * 1024 * 1024]);
        return object("<< /Title (stream) /Length " + bomb.length + " /Filter /FlateDecode >>\nstream\n", bomb);
    }

    private static byte[] spacedKeyword() {
        byte[] bomb = zlib(new byte[4 * 1024 * 1024]);
        return object("<< /Length " + bomb.length + " /Filter /FlateDecode >>\nstream   \n", bomb);
    }

    private static byte[] largeImage() {
        byte[] image = zlib(new byte[4 * 1024 * 1024]);
        return object("<< /Type /XObject /Subtype /Image /Width 2048 /Height 2048 /BitsPerComponent 8 "
                + "/ColorSpace /DeviceGray /Length " + image.length + " /Filter /FlateDecode >>\nstream\n", image);
    }

    private static byte[] unterminated() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(ascii("%PDF-1.4\n1 0 obj\n<< /Filter /FlateDecode >>\nstream\n"));
        out.writeBytes(zlib(new byte[4 * 1024 * 1024]));
        return out.toByteArray();
    }

    private static byte[] longDictionary() {
        byte[] bomb = zlib(new byte[4 * 1024 * 1024]);
        return object("<< /Padding (" + "p".repeat(5_000) + ") /Length " + bomb.length
                + " /Filter /FlateDecode >>\nstream\n", bomb);
    }

    private static byte[] object(String head, byte[] data) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(ascii("%PDF-1.4\n1 0 obj\n" + head));
        out.writeBytes(data);
        out.writeBytes(ascii("\nendstream\nendobj\n"));
        return out.toByteArray();
    }

    private static boolean refuses(Runnable scan) {
        try {
            scan.run();
            return false;
        } catch (UnreadableDocumentException e) {
            return true;
        }
    }

    private static Instant later() {
        return Instant.now().plusSeconds(60);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] zlib(byte[] data) {
        var deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
