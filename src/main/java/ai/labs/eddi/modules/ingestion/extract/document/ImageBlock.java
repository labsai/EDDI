/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

/**
 * An image: embedded in the document, or a rendering of a page or of a region
 * of one.
 *
 * @param imageId
 *            identifies the image among the document's derived images
 * @param w
 *            width in pixels, as stored
 * @param h
 *            height in pixels, as stored
 * @param caption
 *            the nearby caption line taken as the image's alt text, or null
 * @param origin
 *            where the pixels come from
 */
public record ImageBlock(int page, float y, int order, String imageId, int w, int h, String caption, Origin origin)
        implements
            Block {

    /** Where an image's pixels come from. */
    public enum Origin {
        /** An image the document embeds, as it is stored there. */
        EMBEDDED,
        /** A whole page, rendered. */
        PAGE_RENDER,
        /** Part of a page — a figure or a table — rendered. */
        REGION_RENDER
    }
}
