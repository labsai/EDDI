/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC 2047 encoded-word decoding ({@code =?charset?B|Q?text?=}) that never
 * throws.
 * <p>
 * Jakarta Mail's {@code MimeUtility.decodeText} throws on a charset the JVM
 * lacks, which loses the whole subject over one word. This decoder follows the
 * RFC where it matters and is lenient where real mailers are not:
 * <ul>
 * <li>Whitespace <em>between</em> two encoded words is dropped (RFC 2047 §6.2);
 * other whitespace is kept.</li>
 * <li>Adjacent words in the same charset are joined as <em>bytes</em> before
 * decoding, because some mailers split a multi-byte UTF-8 character across two
 * words.</li>
 * <li>An RFC 2231 language suffix ({@code utf-8*de}) is ignored.</li>
 * <li>A word that cannot be decoded (bad base64) is kept as written; an unknown
 * charset is handled by {@link MailCharsets}.</li>
 * </ul>
 */
public final class EncodedWords {

    private static final Pattern WORD = Pattern.compile("=\\?([^?\\s]+)\\?([BbQq])\\?([^?\\s]*)\\?=");

    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]*={0,2}");

    private EncodedWords() {
    }

    /** Decodes every encoded word in {@code text}; null yields null. */
    public static String decode(String text) {
        if (text == null || !text.contains("=?")) {
            return text;
        }
        Matcher matcher = WORD.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        String pendingCharset = null;
        int last = 0;
        while (matcher.find()) {
            String between = text.substring(last, matcher.start());
            boolean adjacent = pendingCharset != null && between.isBlank();
            if (!adjacent) {
                flush(out, pending, pendingCharset);
                pendingCharset = null;
                out.append(between);
            }
            String charset = stripLanguage(matcher.group(1));
            byte[] bytes = decodeWord(matcher.group(2), matcher.group(3));
            if (bytes == null) {
                flush(out, pending, pendingCharset);
                pendingCharset = null;
                out.append(matcher.group());
            } else {
                if (pendingCharset != null && !pendingCharset.equalsIgnoreCase(charset)) {
                    flush(out, pending, pendingCharset);
                }
                pendingCharset = charset;
                pending.writeBytes(bytes);
            }
            last = matcher.end();
        }
        flush(out, pending, pendingCharset);
        out.append(text.substring(last));
        return out.toString();
    }

    private static void flush(StringBuilder out, ByteArrayOutputStream pending, String charset) {
        if (pending.size() > 0) {
            out.append(MailCharsets.decode(pending.toByteArray(), charset).text());
            pending.reset();
        }
    }

    private static String stripLanguage(String charset) {
        int star = charset.indexOf('*');
        return star >= 0 ? charset.substring(0, star) : charset;
    }

    private static byte[] decodeWord(String encoding, String encoded) {
        if ("B".equalsIgnoreCase(encoding)) {
            if (!BASE64.matcher(encoded).matches()) {
                // The MIME decoder skips characters outside the alphabet, so junk
                // would decode to nothing and the word would vanish.
                return null;
            }
            try {
                return Base64.getMimeDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '_') {
                bytes.write(' ');
            } else if (c == '=' && i + 2 < encoded.length()) {
                int value = hex(encoded.charAt(i + 1), encoded.charAt(i + 2));
                if (value < 0) {
                    bytes.write('=');
                } else {
                    bytes.write(value);
                    i += 2;
                }
            } else {
                bytes.write(c & 0xFF);
            }
        }
        return bytes.toByteArray();
    }

    private static int hex(char high, char low) {
        int h = Character.digit(high, 16);
        int l = Character.digit(low, 16);
        return h < 0 || l < 0 ? -1 : (h << 4) | l;
    }
}
