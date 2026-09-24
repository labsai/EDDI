/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Text that is already text: .txt, .md, .json, .xml and friends.
 *
 * <p>
 * The work here is the decoding. A file has no field saying which encoding it
 * used, and guessing wrong is not a visible failure — it is a document full of
 * Ã© that embeds and retrieves as nonsense.
 */
@ApplicationScoped
public class PlainTextExtractor implements DocumentTextExtractor {

    private static final Set<String> MIMES = Set.of(
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "application/json",
            "application/xml",
            "text/xml",
            "application/yaml",
            "text/yaml");

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && MIMES.contains(mimeType.toLowerCase());
    }

    @Override
    public String extract(byte[] content, ExtractionLimits limits) {
        return Extraction.capped(decode(content), limits.maxCharacters());
    }

    /**
     * UTF-8 unless the bytes say otherwise.
     *
     * <p>
     * A byte-order mark is believed, since it is the only in-band statement of
     * encoding these formats have. Failing that, strict UTF-8 is tried: it is what
     * almost everything writes, and — unlike the single-byte encodings — invalid
     * sequences are detectable, so a failure is real evidence rather than a guess.
     * Only then does this fall back to Windows-1252, which accepts any byte and so
     * can never fail, correct or not.
     */
    static String decode(byte[] content) {
        if (startsWith(content, 0xEF, 0xBB, 0xBF)) {
            return new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(content, 0xFE, 0xFF)) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16BE);
        }
        if (startsWith(content, 0xFF, 0xFE)) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16LE);
        }
        try {
            CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = strictUtf8.decode(ByteBuffer.wrap(content));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return new String(content, WINDOWS_1252);
        }
    }

    /**
     * The encoding behind most non-UTF-8 text in practice, and a superset of
     * Latin-1 where they differ — so smart quotes survive instead of becoming
     * control characters.
     */
    private static final Charset WINDOWS_1252 = Charset.isSupported("windows-1252")
            ? Charset.forName("windows-1252")
            : StandardCharsets.ISO_8859_1;

    private static boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((content[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
