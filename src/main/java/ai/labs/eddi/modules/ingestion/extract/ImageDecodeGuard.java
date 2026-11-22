/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDResources;

import java.util.List;
import java.util.Optional;

/**
 * The check an image must pass before PDFBox allocates a raster or a decode
 * buffer for it, on what its dictionary declares.
 *
 * <p>
 * Neither existing bound sees an image raster: {@link PdfStreamBudget} skips
 * image streams, and {@link PdfDecodeBudget} counts what a filter writes, not
 * the raster PDFBox then sizes from {@code /Width} and {@code /Height}. A
 * hundred bytes of dictionary can ask for a 100,000 × 100,000 raster. So the
 * image is refused here when
 * <ul>
 * <li>a dimension is missing, not a positive whole number, or beyond what a
 * raster can hold;</li>
 * <li>its colour space has more than {@value #MAX_COMPONENTS} components;</li>
 * <li>it, a mask PDFBox would decode with it ({@code /SMask}, a stream
 * {@code /Mask}), or the image PDFBox builds when it applies that mask — at the
 * larger of the two widths and the larger of the two heights — has more than
 * {@link ImageLimits#maxPixels()} pixels; or a CCITT image's decode buffer,
 * which PDFBox sizes from {@code /DecodeParms /Columns}, does;</li>
 * <li>what decoding it would take — the decoded samples, which the budget will
 * count, plus the buffers and rasters built from them, which it will not — is
 * more than the thread's budget has left.</li>
 * </ul>
 *
 * <p>
 * Every entry is read <b>the way PDFBox reads it</b>: an inline image's
 * abbreviated key first ({@code /W} before {@code /Width}), an XObject's full
 * key only. A guard that read a different key from the one PDFBox then sizes
 * its raster from would check one number and allocate another. For the same
 * reason the device colour spaces are resolved through the resources'
 * {@code /DefaultGray}, {@code /DefaultRGB} and {@code /DefaultCMYK}, as PDFBox
 * resolves them.
 *
 * <p>
 * Everything is computed in {@code long} with exact arithmetic: a product that
 * would overflow is refused, never wrapped. A colour space whose component
 * count cannot be told is assumed to have four, the most an ordinary one has.
 *
 * <p>
 * A JPEG's own frame header can declare a different size from its dictionary;
 * {@link ImageNormalizer} reads it and checks it here as well
 * ({@link #check(Declared, long, long, int)}).
 */
final class ImageDecodeGuard {

    /** Most components a colour space may have before the image is refused. */
    static final int MAX_COMPONENTS = 32;

    /** Components assumed where the colour space does not say. */
    static final int UNKNOWN_COMPONENTS = 4;

    /** {@link #components} for a colour space with too many components. */
    static final int TOO_MANY = -1;

    /** Bytes per pixel of the RGB(A) image PDFBox builds from any raster. */
    private static final int RGB_BYTES = 4;

    /**
     * Bytes per pixel of applying a mask: the scaled mask, the scaled image, the
     * result.
     */
    private static final int MASKING_BYTES = 1 + RGB_BYTES + RGB_BYTES;

    /** PDFBox's default {@code /Columns} of a CCITT image. */
    private static final int CCITT_DEFAULT_COLUMNS = 1728;

    private ImageDecodeGuard() {
    }

    /** Why an image is not decoded. */
    enum Refusal {
        /**
         * A dimension missing, not a positive whole number, or too large for a raster.
         */
        BAD_DIMENSIONS,
        /** A colour space with more than {@value #MAX_COMPONENTS} components. */
        TOO_MANY_COMPONENTS,
        /** More pixels than {@link ImageLimits#maxPixels()}. */
        TOO_MANY_PIXELS,
        /** More than the decode budget has left. */
        OVER_BUDGET
    }

    /**
     * What an image's dictionary declares.
     *
     * @param width
     *            pixels
     * @param height
     *            pixels
     * @param components
     *            colour components per pixel, 1..{@value #MAX_COMPONENTS}, or
     *            {@link #TOO_MANY}
     * @param bitsPerComponent
     *            1..16
     */
    record Declared(long width, long height, int components, int bitsPerComponent) {

        long pixels() {
            return Math.multiplyExact(width, height);
        }

        /** Bytes of decoded samples: whole rows, each rounded up to a byte. */
        long decodedBytes() {
            long rowBits = Math.multiplyExact(Math.multiplyExact(width, components), bitsPerComponent);
            return Math.multiplyExact((rowBits + 7) / 8, height);
        }

        /**
         * Bytes of the rasters decoding builds at this subsampling: one sample byte per
         * component, plus the RGB(A) image.
         */
        long rasterBytes(int subsampling) {
            return Math.multiplyExact(sampledPixels(subsampling), components + RGB_BYTES);
        }

        long sampledPixels(int subsampling) {
            return Math.multiplyExact(ceilDiv(width, subsampling), ceilDiv(height, subsampling));
        }
    }

    /**
     * An image dictionary entry, read as PDFBox reads it: an inline image's
     * abbreviated key first, then the full one; an XObject's full key only.
     */
    static COSBase entry(COSDictionary dictionary, boolean inline, COSName key, COSName abbreviation) {
        return inline ? dictionary.getDictionaryObject(abbreviation, key) : dictionary.getDictionaryObject(key);
    }

    /**
     * @param image
     *            the image dictionary, or an inline image's parameters
     * @param inline
     *            whether it is an inline image, which decides the keys read
     * @param filters
     *            its filter chain, abbreviations expanded
     *            ({@link ImageNormalizer#filters})
     * @param resources
     *            where named and default colour spaces are looked up; may be null
     * @param maxPixels
     *            per image, per mask and per masked result
     * @param remainingBudget
     *            what the thread's decode budget has left
     * @param subsampling
     *            the subsampling the image would be decoded at, at least 1
     * @return why the image must not be decoded, or empty when it may be
     */
    static Optional<Refusal> check(COSDictionary image, boolean inline, List<String> filters, PDResources resources,
                                   long maxPixels, long remainingBudget, int subsampling) {
        int sampling = Math.max(1, subsampling);
        Optional<Declared> declared = declared(image, inline, resources);
        if (declared.isEmpty()) {
            return Optional.of(Refusal.BAD_DIMENSIONS);
        }
        Declared main = declared.get();
        Optional<Refusal> refusal = check(main, maxPixels, remainingBudget, sampling);
        if (refusal.isPresent()) {
            return refusal;
        }
        try {
            long cost = Math.addExact(main.decodedBytes(), main.rasterBytes(sampling));
            int ccitt = filters.indexOf("CCITTFaxDecode");
            if (ccitt >= 0) {
                // PDFBox sizes this buffer from /Columns, whatever /Width says
                long columns = ccittColumns(image, inline, ccitt);
                if (columns <= 0) {
                    return Optional.of(Refusal.BAD_DIMENSIONS);
                }
                if (Math.multiplyExact(columns, main.height()) > maxPixels) {
                    return Optional.of(Refusal.TOO_MANY_PIXELS);
                }
                cost = Math.addExact(cost, Math.multiplyExact((columns + 7) / 8, main.height()));
            }
            if (!inline) {
                for (COSName key : new COSName[]{COSName.SMASK, COSName.MASK}) {
                    COSBase mask = image.getDictionaryObject(key);
                    if (mask instanceof COSArray) {
                        // A colour key: a mask and an ARGB image at the image's size
                        cost = Math.addExact(cost, Math.multiplyExact(main.sampledPixels(sampling), 1 + RGB_BYTES));
                        continue;
                    }
                    if (!(mask instanceof COSStream maskStream)) {
                        continue;
                    }
                    Optional<Declared> maskDeclared = declared(maskStream, false, null);
                    if (maskDeclared.isEmpty()) {
                        return Optional.of(Refusal.BAD_DIMENSIONS);
                    }
                    Declared masked = maskDeclared.get();
                    if (masked.components() == TOO_MANY) {
                        return Optional.of(Refusal.TOO_MANY_COMPONENTS);
                    }
                    if (masked.pixels() > maxPixels) {
                        return Optional.of(Refusal.TOO_MANY_PIXELS);
                    }
                    // PDFBox applies the mask at the larger width and the larger height of the
                    // two: a 1,568 × 1 image with a 1 × 1,000,000 mask is masked at 1,568 ×
                    // 1,000,000
                    long width = Math.max(ceilDiv(main.width(), sampling), ceilDiv(masked.width(), sampling));
                    long height = Math.max(ceilDiv(main.height(), sampling), ceilDiv(masked.height(), sampling));
                    long result = Math.multiplyExact(width, height);
                    if (result > maxPixels) {
                        return Optional.of(Refusal.TOO_MANY_PIXELS);
                    }
                    cost = Math.addExact(cost, Math.addExact(masked.decodedBytes(), masked.rasterBytes(sampling)));
                    cost = Math.addExact(cost, Math.multiplyExact(result, MASKING_BYTES));
                }
            }
            return cost > remainingBudget ? Optional.of(Refusal.OVER_BUDGET) : Optional.empty();
        } catch (ArithmeticException overflow) {
            return Optional.of(Refusal.OVER_BUDGET);
        }
    }

    /**
     * The checks on a declared size alone: components, pixels, and decoded samples
     * plus rasters against the budget.
     */
    static Optional<Refusal> check(Declared declared, long maxPixels, long remainingBudget, int subsampling) {
        if (declared.components() == TOO_MANY) {
            return Optional.of(Refusal.TOO_MANY_COMPONENTS);
        }
        try {
            if (declared.pixels() > maxPixels) {
                return Optional.of(Refusal.TOO_MANY_PIXELS);
            }
            long cost = Math.addExact(declared.decodedBytes(), declared.rasterBytes(Math.max(1, subsampling)));
            return cost > remainingBudget ? Optional.of(Refusal.OVER_BUDGET) : Optional.empty();
        } catch (ArithmeticException overflow) {
            return Optional.of(Refusal.OVER_BUDGET);
        }
    }

    /**
     * Reads the declared size, or empty when a dimension is missing, not a positive
     * whole number, or more than {@link Integer#MAX_VALUE} (a raster's limit), or
     * the bit depth is not 1..16.
     */
    static Optional<Declared> declared(COSDictionary image, boolean inline, PDResources resources) {
        long width = positiveWhole(entry(image, inline, COSName.WIDTH, COSName.W));
        long height = positiveWhole(entry(image, inline, COSName.HEIGHT, COSName.H));
        if (width <= 0 || height <= 0) {
            return Optional.empty();
        }
        if (isStencil(image, inline)) {
            return Optional.of(new Declared(width, height, 1, 1));
        }
        COSBase bits = entry(image, inline, COSName.BITS_PER_COMPONENT, COSName.BPC);
        int bitsPerComponent = 8;
        if (bits != null) {
            long value = positiveWhole(bits);
            if (value < 1 || value > 16) {
                return Optional.empty();
            }
            bitsPerComponent = (int) value;
        }
        int components = components(entry(image, inline, COSName.COLORSPACE, COSName.CS), resources, true);
        return Optional.of(new Declared(width, height, components, bitsPerComponent));
    }

    /** Whether PDFBox treats the image as a stencil mask. */
    static boolean isStencil(COSDictionary image, boolean inline) {
        return entry(image, inline, COSName.IMAGE_MASK, COSName.IM) instanceof COSBoolean flag && flag.getValue();
    }

    /**
     * The component count of a colour space: 1..{@value #MAX_COMPONENTS},
     * {@link #TOO_MANY} past that, {@value #UNKNOWN_COMPONENTS} where it cannot be
     * told. A device space is replaced by the resources' default for it, and a name
     * that is not a device space is looked up in the resources — once each, never
     * further.
     */
    static int components(COSBase colorSpace, PDResources resources, boolean mayLookUp) {
        if (colorSpace instanceof COSName name) {
            COSDictionary named = mayLookUp && resources != null
                    ? resources.getCOSObject().getCOSDictionary(COSName.COLORSPACE)
                    : null;
            COSName defaultName = defaultFor(name.getName());
            if (named != null && defaultName != null && named.getDictionaryObject(defaultName) != null) {
                return components(named.getDictionaryObject(defaultName), null, false);
            }
            int device = deviceComponents(name.getName());
            if (device > 0) {
                return device;
            }
            if (named != null) {
                return components(named.getDictionaryObject(name), null, false);
            }
            return UNKNOWN_COMPONENTS;
        }
        if (!(colorSpace instanceof COSArray array) || array.size() == 0
                || !(array.getObject(0) instanceof COSName family)) {
            return UNKNOWN_COMPONENTS;
        }
        return switch (family.getName()) {
            case "ICCBased" -> array.size() > 1 && array.getObject(1) instanceof COSStream profile
                    ? bounded(profile.getInt(COSName.N, UNKNOWN_COMPONENTS))
                    : UNKNOWN_COMPONENTS;
            case "Indexed", "I", "Separation", "CalGray" -> 1;
            case "CalRGB", "Lab" -> 3;
            case "DeviceN" -> array.size() > 1 && array.getObject(1) instanceof COSArray names
                    ? bounded(names.size())
                    : TOO_MANY;
            default -> UNKNOWN_COMPONENTS;
        };
    }

    private static COSName defaultFor(String device) {
        return switch (device) {
            case "DeviceGray", "G" -> COSName.DEFAULT_GRAY;
            case "DeviceRGB", "RGB" -> COSName.DEFAULT_RGB;
            case "DeviceCMYK", "CMYK" -> COSName.DEFAULT_CMYK;
            default -> null;
        };
    }

    private static int deviceComponents(String name) {
        return switch (name) {
            case "DeviceGray", "G", "CalGray", "Indexed", "I" -> 1;
            case "DeviceRGB", "RGB", "CalRGB", "Lab" -> 3;
            case "DeviceCMYK", "CMYK" -> 4;
            default -> 0;
        };
    }

    private static int bounded(int components) {
        if (components < 1) {
            return UNKNOWN_COMPONENTS;
        }
        return components > MAX_COMPONENTS ? TOO_MANY : components;
    }

    /**
     * {@code /Columns} of the decode parameters belonging to the CCITT filter at
     * {@code index}, as PDFBox reads them: 1,728 when absent; -1 when not a usable
     * number.
     */
    private static long ccittColumns(COSDictionary image, boolean inline, int index) {
        COSBase parameters = entry(image, inline, COSName.DECODE_PARMS, COSName.DP);
        if (parameters instanceof COSArray array) {
            parameters = index < array.size() ? array.getObject(index) : null;
        }
        if (!(parameters instanceof COSDictionary dictionary)) {
            return CCITT_DEFAULT_COLUMNS;
        }
        COSBase columns = dictionary.getDictionaryObject(COSName.COLUMNS);
        return columns == null ? CCITT_DEFAULT_COLUMNS : positiveWhole(columns);
    }

    /** A positive whole number up to {@link Integer#MAX_VALUE}, else -1. */
    private static long positiveWhole(COSBase value) {
        if (!(value instanceof COSNumber number)) {
            return -1;
        }
        // A real is truncated, as PDFBox reads it; one too large for a long saturates
        // and is refused with the rest
        long whole = number.longValue();
        return whole >= 1 && whole <= Integer.MAX_VALUE ? whole : -1;
    }

    private static long ceilDiv(long value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
