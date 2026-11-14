/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The common encodings of a secret plaintext, so a value-based redaction also
 * finds the secret when a server echoes it back encoded rather than verbatim.
 * <p>
 * Redaction by value replaces exact occurrences. An API that echoes a request
 * header back usually does it verbatim, but not always: a debugging endpoint
 * may return it base64-encoded, URL-encoded as a query string, JSON-escaped
 * inside a string that is itself text, or HTML-escaped in an error page. Each
 * of those left the plaintext legible to every later reader of the conversation
 * — team members, exports, logs — although the redaction ran.
 * <p>
 * Covered, for every value of at least the caller's minimum length:
 * <ul>
 * <li><b>base64</b> of its UTF-8 bytes — standard and URL-safe alphabet, each
 * with and without padding;</li>
 * <li><b>percent-encoding</b> — a space as {@code +} or {@code %20}, hex digits
 * in upper or lower case, {@code ~} escaped or literal;</li>
 * <li><b>JSON string escaping</b> (the text between the quotes) — {@code /}
 * escaped or not, non-ASCII characters literal or as {@code \}{@code uXXXX} in
 * either hex case;</li>
 * <li><b>HTML entity escaping</b> of {@code & < > " '} in the spellings common
 * libraries produce ({@code &quot;}/{@code &#34;},
 * {@code &#39;}/{@code &#x27;}/{@code &#039;});</li>
 * <li>a value that is a whole {@code Basic <base64>} credential also yields the
 * decoded {@code user:password} and the password, when they are long
 * enough.</li>
 * </ul>
 * Not covered, deliberately: reversed or otherwise transformed values, other
 * encodings (hex, base32, double encoding), and a secret whose base64 is part
 * of a <em>longer</em> base64 blob — {@code base64(user:secret)} does not
 * contain {@code base64(secret)} as a substring, because base64 encodes three
 * bytes at a time and the alignment shifts with everything in front of the
 * secret.
 * <p>
 * Every encoded form is at least as long as the value it encodes, so a minimum
 * length applied to the value bounds the forms by the same floor.
 */
public final class SecretEncodings {

    /** A whole {@code Basic} credential, scheme and base64 token only. */
    private static final Pattern BASIC_CREDENTIAL = Pattern.compile("(?i)basic\\s+([A-Za-z0-9+/_-]+={0,2})");

    /** An upper-case percent escape, to be spelled in lower case. */
    private static final Pattern UPPER_PERCENT_ESCAPE = Pattern.compile("%[0-9A-F]{2}");

    /**
     * The spellings of {@code "} and {@code '} that HTML escapers produce: Python
     * {@code html.escape}, Go {@code html.EscapeString}, PHP
     * {@code htmlspecialchars(ENT_QUOTES)}, and escapers that leave the apostrophe.
     */
    private static final List<String[]> HTML_QUOTE_SPELLINGS = List.of(
            new String[]{"&quot;", "&#x27;"},
            new String[]{"&#34;", "&#39;"},
            new String[]{"&quot;", "&#039;"},
            new String[]{"&quot;", "&#39;"},
            new String[]{"&quot;", "'"});

    private SecretEncodings() {
    }

    /**
     * {@code secrets} together with the encoded forms of every one of them that has
     * at least {@code minLength} characters. Shorter values are kept as they are,
     * without encodings; null and empty ones are dropped. Built once per redaction,
     * not per value redacted: the result is the needle set handed to the scrubber.
     *
     * @return a new, mutable set; empty when {@code secrets} is null or empty
     */
    public static Set<String> withEncodedForms(Collection<String> secrets, int minLength) {
        Set<String> needles = new LinkedHashSet<>();
        if (secrets == null) {
            return needles;
        }
        for (String secret : secrets) {
            if (secret == null || secret.isEmpty()) {
                continue;
            }
            needles.add(secret);
            if (secret.length() < minLength) {
                continue;
            }
            needles.addAll(encodedForms(secret));
            for (String part : basicCredentialParts(secret)) {
                if (part.length() >= minLength) {
                    needles.add(part);
                    needles.addAll(encodedForms(part));
                }
            }
        }
        return needles;
    }

    /**
     * Every encoded form of {@code value} listed in the class comment, without
     * {@code value} itself (an alphanumeric value is its own percent, JSON and HTML
     * form).
     */
    static Set<String> encodedForms(String value) {
        Set<String> forms = new LinkedHashSet<>();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        addBase64(forms, Base64.getEncoder().encodeToString(bytes));
        addBase64(forms, Base64.getUrlEncoder().encodeToString(bytes));

        String plus = URLEncoder.encode(value, StandardCharsets.UTF_8);
        for (String spaced : List.of(plus, plus.replace("+", "%20"))) {
            for (String tilde : List.of(spaced, spaced.replace("%7E", "~"))) {
                forms.add(tilde);
                forms.add(lowerHex(tilde));
            }
        }

        for (boolean escapeSlash : new boolean[]{false, true}) {
            forms.add(jsonEscape(value, escapeSlash, false, false));
            forms.add(jsonEscape(value, escapeSlash, false, true));
            forms.add(jsonEscape(value, escapeSlash, true, false));
            forms.add(jsonEscape(value, escapeSlash, true, true));
        }

        for (String[] quotes : HTML_QUOTE_SPELLINGS) {
            forms.add(htmlEscape(value, quotes[0], quotes[1]));
        }

        forms.remove(value);
        forms.remove("");
        return forms;
    }

    private static void addBase64(Set<String> forms, String padded) {
        forms.add(padded);
        int end = padded.length();
        while (end > 0 && padded.charAt(end - 1) == '=') {
            end--;
        }
        forms.add(padded.substring(0, end));
    }

    private static String lowerHex(String percentEncoded) {
        return UPPER_PERCENT_ESCAPE.matcher(percentEncoded).replaceAll(m -> m.group().toLowerCase(Locale.ROOT));
    }

    /**
     * {@code value} escaped as the content of a JSON string.
     *
     * @param asciiOnly
     *            whether every character outside printable ASCII is written as a
     *            {@code \}{@code uXXXX} escape (Python's {@code json.dumps}
     *            default), not only control characters
     * @param upperHex
     *            whether those escapes use upper-case hex digits (Jackson) or lower
     *            case (Python)
     */
    private static String jsonEscape(String value, boolean escapeSlash, boolean asciiOnly, boolean upperHex) {
        var out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '/' -> out.append(escapeSlash ? "\\/" : "/");
                default -> {
                    if (c < 0x20 || asciiOnly && c > 0x7E) {
                        out.append(String.format(upperHex ? "\\u%04X" : "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String htmlEscape(String value, String quot, String apos) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", quot).replace("'", apos);
    }

    /**
     * For a value that is a whole {@code Basic <base64>} credential: the decoded
     * {@code user:password} and the password. Empty for anything else, including a
     * token that does not decode to UTF-8 text with a colon in it.
     */
    static List<String> basicCredentialParts(String value) {
        Matcher matcher = BASIC_CREDENTIAL.matcher(value.strip());
        if (!matcher.matches()) {
            return List.of();
        }
        String token = matcher.group(1);
        byte[] decoded;
        try {
            decoded = token.indexOf('-') >= 0 || token.indexOf('_') >= 0
                    ? Base64.getUrlDecoder().decode(token)
                    : Base64.getDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        String credential;
        try {
            credential = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
        } catch (CharacterCodingException e) {
            return List.of();
        }
        int colon = credential.indexOf(':');
        return colon < 0 ? List.of() : List.of(credential, credential.substring(colon + 1));
    }
}
