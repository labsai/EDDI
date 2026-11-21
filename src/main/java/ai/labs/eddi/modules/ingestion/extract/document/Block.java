/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

import java.util.Comparator;

/**
 * One piece of a document's content in reading order: text, an image, a table
 * or an audio file.
 *
 * <p>
 * Nothing produces blocks yet; they arrive with ordered extraction in a later
 * phase. The types are defined now so the manifest and its consumers are built
 * against the final shape.
 */
public sealed interface Block permits TextBlock, ImageBlock, TableBlock, AudioBlock {

    /**
     * Reading order: page, then top to bottom, then {@link #order()}, which the
     * extractor assigns left to right among blocks at the same height.
     */
    Comparator<Block> READING_ORDER = Comparator.comparingInt(Block::page).thenComparingDouble(Block::y)
            .thenComparingInt(Block::order);

    /** The 1-based page the block starts on. */
    int page();

    /** Distance of the block's top from the top of its page, in PDF points. */
    float y();

    /** Tie-break among blocks at the same page and height. */
    int order();
}
