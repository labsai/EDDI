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

import java.util.Optional;

/**
 * The check an image must pass <b>before</b> PDFBox allocates anything for it,
 * on what its dictionary declares.
 *
 * <p>
 * Neither existing bound sees an image raster: {@link PdfStreamBudget} skips
 * image streams, and {@link PdfDecodeBudget} counts what a filter writes, not
 * the raster PDFBox then sizes from {@code /Width} and {@code /Height}. A
 * hundred bytes of dictionary can ask for a 100,000 × 100,000 raster. So the
 * image, and every mask PDFBox would decode with it ({@code /SMask}, a stream
 * {@code /Mask}), is refused here when
 * <ul>
 * <li>a dimension is missing, not a positive whole number, or beyond what a
 * raster can hold;</li>
 * <li>it declares more than {@link ImageLimits#maxPixels()} pixels;</li>
 * <li>what decoding it would take — the decoded samples, which the budget will
 * count, plus the rasters built from them, which it will not — is more than the
 * thread's budget has left.</li>
 * </ul>
 * Everything is computed in {@code long} with exact arithmetic: a product that
 * would overflow is refused, never wrapped. The colour space's component count
 * is read at the COS level and bounded, and anything it cannot tell is assumed
 * to be four components, the most an ordinary colour space has.
 */
final class ImageDecodeGuard {

    /** Most components one colour space is taken to have (DeviceN's limit). */
    static final int MAX_COMPONENTS = 32;

    /** Components assumed where the colour space does not say. */
    static final int UNKNOWN_COMPONENTS = 4;

    /** Bytes per pixel of the RGB(A) image PDFBox builds from any raster. */
    private static final int RGB_BYTES = 4;

    private ImageDecodeGuard() {
    }

    /** Why an image is not decoded. */
    enum Refusal {
        /**
         * A dimension missing, not a positive whole number, or too large for a raster.
         */
        BAD_DIMENSIONS,
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
     *            colour components per pixel, 1..{@value #MAX_COMPONENTS}
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
            long w = ceilDiv(width, subsampling);
            long h = ceilDiv(height, subsampling);
            return Math.multiplyExact(Math.multiplyExact(w, h), components + RGB_BYTES);
        }
    }

    /**
     * @param image
     *            the image dictionary, or an inline image's parameters
     * @param resources
     *            where a named colour space is looked up; may be null
     * @param maxPixels
     *            per image and per mask
     * @param remainingBudget
     *            what the thread's decode budget has left
     * @param subsampling
     *            the subsampling the image would be decoded at, at least 1
     * @return why the image must not be decoded, or empty when it may be
     */
    static Optional<Refusal> check(COSDictionary image, PDResources resources, long maxPixels, long remainingBudget,
                                   int subsampling) {
        int sampling = Math.max(1, subsampling);
        long cost = 0;
        try {
            Optional<Declared> declared = declared(image, resources);
            if (declared.isEmpty()) {
                return Optional.of(Refusal.BAD_DIMENSIONS);
            }
            Declared main = declared.get();
            if (main.pixels() > maxPixels) {
                return Optional.of(Refusal.TOO_MANY_PIXELS);
            }
            cost = Math.addExact(main.decodedBytes(), main.rasterBytes(sampling));
            for (COSName key : new COSName[]{COSName.SMASK, COSName.MASK}) {
                if (!(image.getDictionaryObject(key) instanceof COSStream mask)) {
                    continue; // a colour-key /Mask is an array: nothing to decode
                }
                Optional<Declared> maskDeclared = declared(mask, null);
                if (maskDeclared.isEmpty()) {
                    return Optional.of(Refusal.BAD_DIMENSIONS);
                }
                if (maskDeclared.get().pixels() > maxPixels) {
                    return Optional.of(Refusal.TOO_MANY_PIXELS);
                }
                // The mask's own samples and rasters, and the masked ARGB image PDFBox
                // builds at the image's size
                cost = Math.addExact(cost, Math.addExact(maskDeclared.get().decodedBytes(),
                        maskDeclared.get().rasterBytes(sampling)));
                cost = Math.addExact(cost, Math.multiplyExact(
                        Math.multiplyExact(ceilDiv(main.width(), sampling), ceilDiv(main.height(), sampling)),
                        RGB_BYTES));
            }
        } catch (ArithmeticException overflow) {
            return Optional.of(Refusal.OVER_BUDGET);
        }
        return cost > remainingBudget ? Optional.of(Refusal.OVER_BUDGET) : Optional.empty();
    }

    /**
     * Reads the declared size, or empty when a dimension is missing, not a positive
     * whole number, or more than {@link Integer#MAX_VALUE} (a raster's limit).
     */
    static Optional<Declared> declared(COSDictionary image, PDResources resources) {
        long width = positiveWhole(image.getDictionaryObject(COSName.WIDTH, COSName.W));
        long height = positiveWhole(image.getDictionaryObject(COSName.HEIGHT, COSName.H));
        if (width <= 0 || height <= 0) {
            return Optional.empty();
        }
        boolean stencil = image.getDictionaryObject(COSName.IMAGE_MASK, COSName.IM) instanceof COSBoolean flag
                && flag.getValue();
        if (stencil) {
            return Optional.of(new Declared(width, height, 1, 1));
        }
        COSBase bits = image.getDictionaryObject(COSName.BITS_PER_COMPONENT, COSName.BPC);
        int bitsPerComponent = 8;
        if (bits != null) {
            long value = positiveWhole(bits);
            if (value < 1 || value > 16) {
                return Optional.empty();
            }
            bitsPerComponent = (int) value;
        }
        int components = components(image.getDictionaryObject(COSName.COLORSPACE, COSName.CS), resources, true);
        return Optional.of(new Declared(width, height, components, bitsPerComponent));
    }

    /**
     * The component count a colour space declares, bounded to
     * 1..{@value #MAX_COMPONENTS}; {@value #UNKNOWN_COMPONENTS} where it cannot be
     * told. A name not of a device space is looked up once in the resources, never
     * further.
     */
    static int components(COSBase colorSpace, PDResources resources, boolean mayLookUp) {
        if (colorSpace instanceof COSName name) {
            int device = deviceComponents(name.getName());
            if (device > 0) {
                return device;
            }
            if (mayLookUp && resources != null) {
                COSDictionary named = resources.getCOSObject().getCOSDictionary(COSName.COLORSPACE);
                if (named != null) {
                    return components(named.getDictionaryObject(name), null, false);
                }
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
                    : MAX_COMPONENTS;
            default -> UNKNOWN_COMPONENTS;
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
        return components < 1 ? UNKNOWN_COMPONENTS : Math.min(components, MAX_COMPONENTS);
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
