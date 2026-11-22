/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.extract.ImageDecodeGuard.Refusal;
import ai.labs.eddi.modules.ingestion.extract.PdfImageCollector.Placement;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns one collected image into bytes a model can take: the embedded JPEG as
 * it is, or a decoded, downscaled and re-encoded PNG or JPEG — <b>within hard
 * bounds</b>, and only while the document is open and its
 * {@link PdfDecodeBudget} armed on this thread.
 *
 * <ol>
 * <li><b>Unsupported codecs</b> — JBIG2 and JPEG 2000 — are refused before
 * PDFBox is asked anything; no codec for them ships.</li>
 * <li><b>{@link ImageDecodeGuard}</b> checks the declared size of the image,
 * its masks and its decode buffers, read as PDFBox reads them, against the
 * pixel cap and what the budget has left, before PDFBox builds a raster or
 * buffer.</li>
 * <li><b>A JPEG's frame header</b>, which is what its decoder sizes the raster
 * from, must agree with the dictionary and pass the same checks — at full size
 * for an inline image, which PDFBox decodes without subsampling.</li>
 * <li><b>Pass-through:</b> a stream whose only filter is {@code DCTDecode}, an
 * 8-bit baseline or progressive JPEG of one or three components in plain gray
 * or RGB, with no masks, {@code /Decode} array, decode parameters or stencil
 * flag, which fits {@link ImageLimits#maxEdgePx()} and
 * {@link ImageLimits#maxEncodedBytes()}, is returned byte for byte from the raw
 * stream: nothing is decoded.</li>
 * <li><b>Everything else</b> is decoded by PDFBox — subsampled when it is
 * larger than {@link ImageLimits#maxEdgePx()}, so the raster is built at
 * roughly the target size — scaled down to fit, and encoded: JPEG for an image
 * that was a JPEG (a CMYK or YCCK one, say), PNG otherwise, JPEG again if the
 * PNG is over {@link ImageLimits#maxEncodedBytes()}, then smaller until it
 * fits.</li>
 * </ol>
 *
 * <p>
 * An image that cannot be decoded or does not fit is a {@link Refused} result,
 * so one bad figure costs that figure, not the document. Two things do refuse
 * the document, because they are the bounds every PDF read shares: the decode
 * budget running out (a Flate bomb inside an image stream, say, which the raw
 * pre-scan does not open) and the deadline.
 *
 * <p>
 * No store, memory or CDI: callers own persistence. Never call it from another
 * thread than the one the budget is armed on.
 */
public final class ImageNormalizer {

    /** Re-encoding quality of a JPEG. */
    private static final float JPEG_QUALITY = 0.85f;

    /** How often an over-large result is halved before it is refused. */
    private static final int MAX_SHRINKS = 3;

    /** How far into a JPEG its header is looked for. */
    private static final int MAX_JPEG_HEADER_BYTES = 64 * 1024;

    private static final Map<String, String> FILTER_ABBREVIATIONS = Map.of("AHx", "ASCIIHexDecode", "A85",
            "ASCII85Decode", "LZW", "LZWDecode", "Fl", "FlateDecode", "RL", "RunLengthDecode", "CCF",
            "CCITTFaxDecode", "DCT", "DCTDecode");

    /**
     * Builds a PDFBox image for a placement and decodes it — the seam the bounds
     * are tested through: whatever the guard refuses never reaches it.
     */
    @FunctionalInterface
    interface Decoder {
        BufferedImage decode(Placement placement, int subsampling) throws IOException;
    }

    /** Why an image produced no bytes. */
    public enum Reason {
        /** JBIG2 or JPEG 2000: no codec for it ships. */
        UNSUPPORTED_FORMAT,
        /**
         * A dimension missing or not usable, or a JPEG whose frame header disagrees
         * with its dictionary.
         */
        BAD_DIMENSIONS,
        /** A colour space with more than 32 components. */
        TOO_MANY_COMPONENTS,
        /** More pixels than {@link ImageLimits#maxPixels()}. */
        TOO_MANY_PIXELS,
        /** More than the decode budget has left. */
        OVER_BUDGET,
        /** An inline image over the inline caps, kept without its bytes. */
        INLINE_TOO_LARGE,
        /** PDFBox could not decode it. */
        UNDECODABLE,
        /** Even shrunk, more than {@link ImageLimits#maxEncodedBytes()}. */
        TOO_LARGE
    }

    /** What normalizing one image gave. */
    public sealed interface Result permits Encoded, Refused {
    }

    /**
     * An image ready to send.
     *
     * @param bytes
     *            the encoded image
     * @param mimeType
     *            {@code image/jpeg} or {@code image/png}
     * @param width
     *            pixels
     * @param height
     *            pixels
     * @param passThrough
     *            whether {@code bytes} are the embedded stream, unchanged
     */
    public record Encoded(byte[] bytes, String mimeType, int width, int height, boolean passThrough) implements Result {
    }

    /** An image that produced no bytes, and why. */
    public record Refused(Reason reason) implements Result {
    }

    private final Decoder decoder;

    /** Decodes with PDFBox. */
    public ImageNormalizer() {
        this(ImageNormalizer::decodeWithPdfBox);
    }

    ImageNormalizer(Decoder decoder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * @throws IllegalStateException
     *             when no decode budget is armed on this thread
     * @throws UnreadableDocumentException
     *             when the deadline passes
     */
    public Result normalize(Placement placement, ImageLimits limits, Instant deadline) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(deadline, "deadline");
        ImageLimits bounds = limits == null ? ImageLimits.defaults() : limits;
        long remaining = PdfDecodeBudget.remaining();
        if (remaining < 0) {
            throw new IllegalStateException("Images are decoded only under an armed PdfDecodeBudget");
        }
        requireBefore(deadline);
        COSDictionary dictionary = placement.dictionary();
        boolean inline = placement.inline();
        List<String> filters = filters(dictionary, inline);
        if (filters.contains("JBIG2Decode") || filters.contains("JPXDecode")) {
            return new Refused(Reason.UNSUPPORTED_FORMAT);
        }
        if (inline && placement.inlineData() == null) {
            return new Refused(Reason.INLINE_TOO_LARGE);
        }
        var declared = ImageDecodeGuard.declared(dictionary, inline, placement.resources());
        if (declared.isEmpty()) {
            return new Refused(Reason.BAD_DIMENSIONS);
        }
        int subsampling = subsampling(declared.get(), bounds.maxEdgePx());
        var refusal = ImageDecodeGuard.check(dictionary, inline, filters, placement.resources(), bounds.maxPixels(),
                remaining, subsampling);
        if (refusal.isPresent()) {
            return new Refused(reason(refusal.get()));
        }
        try {
            JpegHeader header = null;
            if (filters.contains("DCTDecode")) {
                // The JPEG decoder sizes its raster from the frame header, not the
                // dictionary: the two must agree, and the header must pass the guard too —
                // at full size for an inline image, which PDFBox decodes without subsampling
                if (filters.indexOf("DCTDecode") != filters.size() - 1 || (inline && filters.size() > 1)) {
                    return new Refused(Reason.UNSUPPORTED_FORMAT);
                }
                header = JpegHeader.read(jpegHead(placement, filters));
                if (header == null || header.components() < 1 || header.components() > 4) {
                    return new Refused(Reason.UNDECODABLE);
                }
                if (header.width() != declared.get().width() || header.height() != declared.get().height()) {
                    return new Refused(Reason.BAD_DIMENSIONS);
                }
                var frame = new ImageDecodeGuard.Declared(header.width(), header.height(), header.components(), 8);
                var frameRefusal = ImageDecodeGuard.check(frame, bounds.maxPixels(), PdfDecodeBudget.remaining(),
                        inline ? 1 : subsampling);
                if (frameRefusal.isPresent()) {
                    return new Refused(reason(frameRefusal.get()));
                }
            }
            Encoded passed = header == null ? null : passThrough(placement, filters, declared.get(), header, bounds);
            if (passed != null) {
                return passed;
            }
            BufferedImage decoded = decoder.decode(placement, subsampling);
            PdfDecodeBudget.requireNotExceeded();
            requireBefore(deadline);
            if (decoded == null) {
                return new Refused(Reason.UNDECODABLE);
            }
            return encode(decoded, filters.contains("DCTDecode"), bounds, deadline);
        } catch (PdfDecodeBudget.BudgetExceeded | UnreadableDocumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // A budget passed inside PDFBox may surface as anything; it still refuses the
            // document
            PdfDecodeBudget.requireNotExceeded();
            return new Refused(Reason.UNDECODABLE);
        }
    }

    private static Reason reason(Refusal refusal) {
        return switch (refusal) {
            case BAD_DIMENSIONS -> Reason.BAD_DIMENSIONS;
            case TOO_MANY_COMPONENTS -> Reason.TOO_MANY_COMPONENTS;
            case TOO_MANY_PIXELS -> Reason.TOO_MANY_PIXELS;
            case OVER_BUDGET -> Reason.OVER_BUDGET;
        };
    }

    /**
     * The largest whole subsampling that keeps the longer edge at or above the
     * target, so decoding builds a raster near the target size and scaling only
     * ever shrinks.
     */
    static int subsampling(ImageDecodeGuard.Declared declared, int maxEdgePx) {
        long longer = Math.max(declared.width(), declared.height());
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, longer / Math.max(1, maxEdgePx)));
    }

    /**
     * The filter chain, read as PDFBox reads it ({@code /F} first for an inline
     * image; only {@code /Filter} for an XObject, where {@code /F} is a file
     * specification), abbreviations expanded; empty when there is none.
     */
    static List<String> filters(COSDictionary dictionary, boolean inline) {
        COSBase filter = ImageDecodeGuard.entry(dictionary, inline, COSName.FILTER, COSName.F);
        List<String> names = new ArrayList<>();
        if (filter instanceof COSName name) {
            names.add(name.getName());
        } else if (filter instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.getObject(i) instanceof COSName name) {
                    names.add(name.getName());
                }
            }
        }
        names.replaceAll(name -> FILTER_ABBREVIATIONS.getOrDefault(name, name));
        return names;
    }

    /**
     * The embedded JPEG unchanged, when it can be sent as it is and look the same:
     * the stream is nothing but a baseline or progressive 8-bit JPEG of one or
     * three components; its colour space is plain gray or RGB (a device, calibrated
     * or one- or three-component ICC space, with no default colour space in the
     * resources replacing it); it has no masks, no {@code /Decode} array, no decode
     * parameters and is no stencil; its frame header agrees with the dictionary;
     * and it fits the size limits. Else null, and the image is decoded.
     */
    private static Encoded passThrough(Placement placement, List<String> filters, ImageDecodeGuard.Declared declared,
                                       JpegHeader header, ImageLimits limits)
            throws IOException {
        COSDictionary dictionary = placement.dictionary();
        boolean inline = placement.inline();
        if (!filters.equals(List.of("DCTDecode")) || !plainGrayOrRgb(dictionary, inline, placement.resources())
                || header.components() != declared.components() || !header.baselineOrProgressive8Bit()
                || ImageDecodeGuard.isStencil(dictionary, inline)
                || ImageDecodeGuard.entry(dictionary, inline, COSName.DECODE, COSName.D) != null
                || ImageDecodeGuard.entry(dictionary, inline, COSName.DECODE_PARMS, COSName.DP) != null
                || (!inline && (dictionary.getDictionaryObject(COSName.SMASK) != null
                        || dictionary.getDictionaryObject(COSName.MASK) != null))
                || declared.width() > limits.maxEdgePx() || declared.height() > limits.maxEdgePx()) {
            return null;
        }
        byte[] raw = rawBytes(placement, limits.maxEncodedBytes());
        if (raw == null) {
            return null;
        }
        return new Encoded(raw, "image/jpeg", header.width(), header.height(), true);
    }

    /**
     * Whether the colour space is one whose JPEG samples a viewer shows as they
     * are.
     */
    private static boolean plainGrayOrRgb(COSDictionary dictionary, boolean inline, PDResources resources) {
        COSBase colorSpace = ImageDecodeGuard.entry(dictionary, inline, COSName.COLORSPACE, COSName.CS);
        COSDictionary named = resources == null ? null : resources.getCOSObject().getCOSDictionary(COSName.COLORSPACE);
        if (colorSpace instanceof COSName name) {
            return switch (name.getName()) {
                case "DeviceGray", "G" -> named == null || named.getDictionaryObject(COSName.DEFAULT_GRAY) == null;
                case "DeviceRGB", "RGB" -> named == null || named.getDictionaryObject(COSName.DEFAULT_RGB) == null;
                default -> false;
            };
        }
        if (colorSpace instanceof COSArray array && array.size() > 1 && array.getObject(0) instanceof COSName family) {
            return switch (family.getName()) {
                case "CalGray", "CalRGB" -> true;
                case "ICCBased" -> array.getObject(1) instanceof COSStream profile
                        && (profile.getInt(COSName.N) == 1 || profile.getInt(COSName.N) == 3);
                default -> false;
            };
        }
        return false;
    }

    /**
     * The first bytes of the JPEG inside the stream: the raw bytes when DCT is the
     * only filter, else what the filters before it decode to (counted by the
     * budget).
     */
    private static byte[] jpegHead(Placement placement, List<String> filters) throws IOException {
        if (placement.inline()) {
            byte[] data = placement.inlineData();
            return data.length <= MAX_JPEG_HEADER_BYTES ? data : Arrays.copyOf(data, MAX_JPEG_HEADER_BYTES);
        }
        try (InputStream in = filters.size() == 1
                ? placement.stream().createRawInputStream()
                : new PDStream(placement.stream()).createInputStream(List.of(COSName.DCT_DECODE.getName()))) {
            return in.readNBytes(MAX_JPEG_HEADER_BYTES);
        }
    }

    /**
     * The stream's stored bytes, or null when there are more than {@code max}. An
     * inline image's bytes lose the whitespace PDFBox keeps before {@code EI}.
     */
    private static byte[] rawBytes(Placement placement, long max) throws IOException {
        if (placement.inline()) {
            byte[] data = placement.inlineData();
            int end = data.length;
            while (end > 0 && isWhitespace(data[end - 1])) {
                end--;
            }
            return end <= max ? Arrays.copyOf(data, end) : null;
        }
        int cap = (int) Math.min(Integer.MAX_VALUE - 8, max + 1);
        try (InputStream raw = placement.stream().createRawInputStream()) {
            byte[] bytes = raw.readNBytes(cap);
            return bytes.length > max ? null : bytes;
        }
    }

    private static BufferedImage decodeWithPdfBox(Placement placement, int subsampling) throws IOException {
        PDImage image = placement.inline()
                ? new PDInlineImage(placement.dictionary(), placement.inlineData(), placement.resources())
                : new PDImageXObject(new PDStream(placement.stream()), placement.resources());
        return image.getImage(null, subsampling);
    }

    private static Result encode(BufferedImage decoded, boolean wasJpeg, ImageLimits limits, Instant deadline)
            throws IOException {
        BufferedImage image = fit(decoded, limits.maxEdgePx());
        boolean alpha = image.getColorModel().hasAlpha();
        for (int shrink = 0; shrink <= MAX_SHRINKS; shrink++) {
            requireBefore(deadline);
            if (!wasJpeg || alpha) {
                byte[] png = png(image);
                if (png.length <= limits.maxEncodedBytes()) {
                    return new Encoded(png, "image/png", image.getWidth(), image.getHeight(), false);
                }
            }
            byte[] jpeg = jpeg(image);
            if (jpeg.length <= limits.maxEncodedBytes()) {
                return new Encoded(jpeg, "image/jpeg", image.getWidth(), image.getHeight(), false);
            }
            if (image.getWidth() < 2 || image.getHeight() < 2) {
                break;
            }
            image = scale(image, image.getWidth() / 2, image.getHeight() / 2);
        }
        return new Refused(Reason.TOO_LARGE);
    }

    /** Scaled down so the longer edge is at most {@code maxEdge}; never up. */
    static BufferedImage fit(BufferedImage image, int maxEdge) {
        int longer = Math.max(image.getWidth(), image.getHeight());
        if (longer <= maxEdge) {
            return image;
        }
        double factor = (double) maxEdge / longer;
        return scale(image, Math.max(1, (int) Math.round(image.getWidth() * factor)),
                Math.max(1, (int) Math.round(image.getHeight() * factor)));
    }

    private static BufferedImage scale(BufferedImage image, int width, int height) {
        boolean alpha = image.getColorModel().hasAlpha();
        var scaled = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(image, "png", stream)) {
                throw new IOException("No PNG writer");
            }
        }
        return out.toByteArray();
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        BufferedImage opaque = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB && image.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = opaque.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(opaque, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static boolean isWhitespace(byte b) {
        return b == 0x20 || b == 0x0A || b == 0x0D || b == 0x09 || b == 0x0C || b == 0x00;
    }

    private static void requireBefore(Instant deadline) {
        if (Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted()) {
            throw new UnreadableDocumentException(BoundedTextStripper.TOO_LONG);
        }
    }

    /**
     * What a JPEG's frame header says: the size and the component count, read from
     * the first start-of-frame marker within {@value #MAX_JPEG_HEADER_BYTES} bytes.
     */
    record JpegHeader(int width, int height, int components, int frameMarker, int precision) {

        /**
         * A frame of the kinds every JPEG reader takes: baseline, extended or
         * progressive, 8 bits.
         */
        boolean baselineOrProgressive8Bit() {
            return frameMarker >= 0xC0 && frameMarker <= 0xC2 && precision == 8;
        }

        /** The header, or null when the bytes are not a JPEG with a readable frame. */
        static JpegHeader read(byte[] jpeg) {
            if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
                return null;
            }
            int limit = Math.min(jpeg.length, MAX_JPEG_HEADER_BYTES);
            int at = 2;
            while (at + 4 <= limit) {
                if ((jpeg[at] & 0xFF) != 0xFF) {
                    return null;
                }
                int marker = jpeg[at + 1] & 0xFF;
                if (marker == 0xFF) {
                    at++; // fill byte
                    continue;
                }
                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                    at += 2; // no length
                    continue;
                }
                if (marker == 0xD9 || marker == 0xDA) {
                    return null; // end of image or start of scan before any frame
                }
                int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
                if (length < 2) {
                    return null;
                }
                boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8
                        && marker != 0xCC;
                if (frame) {
                    if (at + 10 > limit || length < 8) {
                        return null;
                    }
                    int height = ((jpeg[at + 5] & 0xFF) << 8) | (jpeg[at + 6] & 0xFF);
                    int width = ((jpeg[at + 7] & 0xFF) << 8) | (jpeg[at + 8] & 0xFF);
                    int components = jpeg[at + 9] & 0xFF;
                    return width > 0 && height > 0
                            ? new JpegHeader(width, height, components, marker, jpeg[at + 4] & 0xFF)
                            : null;
                }
                at += 2 + length;
            }
            return null;
        }
    }
}
