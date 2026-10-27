/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes bytes in whatever charset a mail claims — or does not claim, or
 * claims wrongly.
 * <p>
 * Mail in the wild declares {@code us-ascii} and carries UTF-8, declares
 * {@code iso-8859-1} and carries Windows-1252 quotes, or names a charset no JVM
 * has ever heard of. None of that may throw: one undecodable part must not cost
 * the rest of the message. The rules:
 * <ul>
 * <li>A known charset decodes with replacement characters for bad bytes.</li>
 * <li>No charset, {@code us-ascii}, or an unknown one: strict UTF-8 first (it
 * almost never decodes non-UTF-8 text by accident), then Windows-1252, which
 * maps every byte.</li>
 * <li>{@code iso-8859-1} decodes as Windows-1252, its superset, as browsers
 * do.</li>
 * </ul>
 */
public final class MailCharsets {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** Names mail uses that the JDK does not know, or knows as something worse. */
    private static final Map<String, String> ALIASES = Map.of(
            "utf8", "UTF-8",
            "unicode-1-1-utf-8", "UTF-8",
            "latin1", "windows-1252",
            "latin-1", "windows-1252",
            "iso-8859-1", "windows-1252",
            "iso8859-1", "windows-1252",
            "gb2312", "GBK",
            "ks_c_5601-1987", "x-windows-949",
            "x-unknown", "");

    private MailCharsets() {
    }

    /**
     * The decoded text, and whether the declared charset was one this decoder had
     * to ignore.
     */
    public record Decoded(String text, boolean charsetUnknown) {
    }

    /**
     * Decodes {@code bytes} under the declared charset name (which may be null).
     */
    public static Decoded decode(byte[] bytes, String declaredCharset) {
        if (bytes == null || bytes.length == 0) {
            return new Decoded("", false);
        }
        String name = declaredCharset == null ? "" : declaredCharset.strip().replace("\"", "").replace("'", "").toLowerCase(Locale.ROOT);
        String aliased = ALIASES.getOrDefault(name, name);
        if (aliased.isEmpty() || "us-ascii".equals(aliased) || "ascii".equals(aliased)) {
            return new Decoded(utf8OrWindows1252(bytes), false);
        }
        Charset charset = lookup(aliased);
        if (charset == null) {
            return new Decoded(utf8OrWindows1252(bytes), true);
        }
        if (StandardCharsets.UTF_8.equals(charset)) {
            return new Decoded(utf8OrWindows1252(bytes), false);
        }
        return new Decoded(new String(bytes, charset), false);
    }

    /** A charset by name, or null when the JDK does not have it. */
    static Charset lookup(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return null;
        }
    }

    private static String utf8OrWindows1252(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, WINDOWS_1252);
        }
    }
}
