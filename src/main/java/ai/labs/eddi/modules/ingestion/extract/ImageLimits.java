/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

/**
 * What collecting and decoding a PDF's embedded images may cost, on top of the
 * document's {@link ExtractionLimits} and its {@link PdfDecodeBudget}.
 *
 * <p>
 * A non-positive value given to the constructor falls back to the default.
 *
 * @param maxPixels
 *            most pixels one image (or one of its masks) may declare before it
 *            is decoded — {@code eddi.documents.images.max-pixels}, default
 *            {@value #DEFAULT_MAX_PIXELS}. Checked on the dictionary, before
 *            anything is allocated
 * @param maxEdgePx
 *            longest edge of a normalized image; larger images are decoded
 *            subsampled and scaled down to it. Default
 *            {@value #DEFAULT_MAX_EDGE_PX}
 * @param maxEncodedBytes
 *            most bytes one normalized image may take, passed through or
 *            re-encoded. Default 10 MiB, the deployment's
 *            {@code eddi.attachments.max-forward-bytes} default
 * @param maxImages
 *            most image draws one collection records; past it the collection
 *            stops and says it was cut. Default {@value #DEFAULT_MAX_IMAGES}
 * @param maxOperators
 *            most content-stream operators one collection interprets, form
 *            XObjects included however often they are drawn; past it the
 *            collection stops and says it was cut. Default
 *            {@value #DEFAULT_MAX_OPERATORS}
 */
public record ImageLimits(long maxPixels, int maxEdgePx, long maxEncodedBytes, int maxImages, long maxOperators) {

    public static final long DEFAULT_MAX_PIXELS = 40_000_000L;
    public static final int DEFAULT_MAX_EDGE_PX = 1568;
    public static final long DEFAULT_MAX_ENCODED_BYTES = 10L * 1024 * 1024;
    public static final int DEFAULT_MAX_IMAGES = 10_000;
    public static final long DEFAULT_MAX_OPERATORS = 5_000_000L;

    public ImageLimits {
        maxPixels = maxPixels > 0 ? maxPixels : DEFAULT_MAX_PIXELS;
        maxEdgePx = maxEdgePx > 0 ? maxEdgePx : DEFAULT_MAX_EDGE_PX;
        maxEncodedBytes = maxEncodedBytes > 0 ? maxEncodedBytes : DEFAULT_MAX_ENCODED_BYTES;
        maxImages = maxImages > 0 ? maxImages : DEFAULT_MAX_IMAGES;
        maxOperators = maxOperators > 0 ? maxOperators : DEFAULT_MAX_OPERATORS;
    }

    /** Every default. */
    public static ImageLimits defaults() {
        return new ImageLimits(0, 0, 0, 0, 0);
    }

    /** The same limits with a different pixel cap, as the operator sets it. */
    public ImageLimits withMaxPixels(long pixels) {
        return new ImageLimits(pixels, maxEdgePx, maxEncodedBytes, maxImages, maxOperators);
    }

    /** The same limits with a different longest edge, as the designer sets it. */
    public ImageLimits withMaxEdgePx(int edge) {
        return new ImageLimits(maxPixels, edge, maxEncodedBytes, maxImages, maxOperators);
    }
}
