/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks every brand image passes before it is stored, whether uploaded or
 * read from {@code eddi.branding.assets-dir}.
 * <p>
 * The type is decided from the magic bytes alone, never from a file name or the
 * client's {@code Content-Type}. An SVG is a document that runs on EDDI's own
 * origin when opened directly, so uploads would make it a stored-XSS vector
 * (plan R10): anything that can execute or fetch is refused, and the public
 * endpoint additionally serves every SVG under a sandbox CSP.
 */
public final class BrandAssetValidator {

    public static final int MAX_BYTES = 512 * 1024;

    public static final String PNG = "image/png";
    public static final String WEBP = "image/webp";
    public static final String ICO = "image/x-icon";
    public static final String SVG = "image/svg+xml";

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /**
     * Elements that run script, embed another document, or animate an attribute
     * into a {@code javascript:} URL — with or without a namespace prefix
     * ({@code <svg:script>} is the same element to an XML parser).
     */
    private static final Pattern FORBIDDEN_ELEMENT = Pattern.compile(
            "<(?:[a-z0-9_.-]{1,64}:)?(script|foreignobject|iframe|embed|object|handler|listener|animate|set)(?![a-z0-9_.:-])",
            Pattern.CASE_INSENSITIVE);

    /**
     * An event-handler attribute: {@code on…=} after whitespace, a quote or the
     * slash of {@code <svg/onload=…>}.
     */
    private static final Pattern EVENT_HANDLER = Pattern.compile("[\\s/'\"]on[a-z0-9_.:-]*\\s*=", Pattern.CASE_INSENSITIVE);

    /**
     * {@code href} under any namespace prefix ({@code xlink:href}), quoted or not.
     */
    private static final Pattern HREF = Pattern.compile("(?:^|[\\s/'\"])(?:[a-z0-9_.-]{1,64}:)?href\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]*)",
            Pattern.CASE_INSENSITIVE);

    /** The target of a CSS {@code url(…)}, in a style attribute or element. */
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*['\"]?\\s*([^'\")\\s]*)", Pattern.CASE_INSENSITIVE);

    private BrandAssetValidator() {
    }

    /**
     * Validates {@code data} for {@code slot} and returns it as a storable asset.
     *
     * @throws BrandingException
     *             413 over the size cap, 415 for a type the slot does not accept,
     *             400 for an SVG that could run script or load anything
     */
    public static BrandAsset validate(BrandAssetSlot slot, byte[] data) {
        if (data == null || data.length == 0) {
            throw BrandingException.asset(BrandingException.INVALID, "The image is empty.");
        }
        if (data.length > MAX_BYTES) {
            throw BrandingException.asset(BrandingException.TOO_LARGE,
                    "The image is " + data.length + " bytes; at most " + MAX_BYTES + " (512 KB) are accepted.");
        }
        String type = detectType(data);
        if (type == null) {
            throw BrandingException.asset(BrandingException.UNSUPPORTED_TYPE, "Only PNG, WebP and SVG images are accepted"
                    + (slot.acceptsIco() ? ", and ICO for the favicon." : ". ICO is accepted for the favicon only."));
        }
        if (ICO.equals(type) && !slot.acceptsIco()) {
            throw BrandingException.asset(BrandingException.UNSUPPORTED_TYPE, "ICO is accepted for the favicon only.");
        }
        if (SVG.equals(type)) {
            String problem = svgProblem(new String(data, StandardCharsets.UTF_8));
            if (problem != null) {
                throw BrandingException.asset(BrandingException.INVALID, "This SVG cannot be used: " + problem + ".");
            }
        }
        return new BrandAsset(slot, hash(data), type, data.clone());
    }

    /** {@code sha256:<hex>} of the bytes; the asset's identity and ETag. */
    public static String hash(byte[] data) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The content type from the magic bytes, or {@code null} if not accepted. */
    static String detectType(byte[] data) {
        if (startsWith(data, PNG_MAGIC)) {
            return PNG;
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' && data[8] == 'W' && data[9] == 'E'
                && data[10] == 'B' && data[11] == 'P') {
            return WEBP;
        }
        if (data.length >= 6 && data[0] == 0 && data[1] == 0 && data[2] == 1 && data[3] == 0 && (data[4] != 0 || data[5] != 0)) {
            return ICO;
        }
        return looksLikeSvg(data) ? SVG : null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Valid UTF-8 whose first markup is an XML declaration, comment, doctype or the
     * {@code <svg} element itself, and which has an {@code <svg} element.
     */
    private static boolean looksLikeSvg(byte[] data) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return false;
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String start = text.stripLeading().toLowerCase(Locale.ROOT);
        boolean svgStart = start.startsWith("<?xml") || start.startsWith("<!--") || start.startsWith("<!doctype svg")
                || start.startsWith("<svg");
        return svgStart && start.contains("<svg");
    }

    /**
     * Why an SVG is refused, or {@code null} when it is acceptable.
     * <p>
     * The checks run on the raw text, lower-cased, and the {@code javascript:}
     * check also on a copy with all whitespace removed, since a URL parser drops
     * tabs and newlines inside a scheme. Character references are refused outright
     * ({@code &#106;avascript:} decodes to a scheme the text checks cannot see), as
     * are entity declarations, which an XML parser would expand into markup.
     */
    static String svgProblem(String svg) {
        String lower = svg.toLowerCase(Locale.ROOT);
        Matcher element = FORBIDDEN_ELEMENT.matcher(svg);
        if (element.find()) {
            return "it contains a <" + element.group(1).toLowerCase(Locale.ROOT) + "> element";
        }
        if (lower.contains("<!entity")) {
            return "it declares an XML entity";
        }
        if (lower.contains("&#")) {
            return "it contains a character reference (&#…;)";
        }
        if (EVENT_HANDLER.matcher(svg).find()) {
            return "it has an event-handler attribute (on…=)";
        }
        String compact = lower.replaceAll("[\\s\\p{Cntrl}]", "");
        if (compact.contains("javascript:") || compact.contains("vbscript:")) {
            return "it contains a javascript: URL";
        }
        if (lower.contains("@import")) {
            return "its style imports another stylesheet";
        }
        Matcher href = HREF.matcher(svg);
        while (href.find()) {
            String value = unquote(href.group(1)).strip();
            if (!isLocalReference(value)) {
                return "it links to \"" + abbreviate(value) + "\"; only #fragment and data:image/ references are allowed";
            }
        }
        Matcher url = CSS_URL.matcher(svg);
        while (url.find()) {
            String value = url.group(1).strip();
            if (!isLocalReference(value)) {
                return "its style loads \"" + abbreviate(value) + "\"; only url(#…) and data:image/ are allowed";
            }
        }
        return null;
    }

    private static boolean isLocalReference(String value) {
        return value.startsWith("#") || value.toLowerCase(Locale.ROOT).startsWith("data:image/");
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String abbreviate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 60) + "…";
    }
}
