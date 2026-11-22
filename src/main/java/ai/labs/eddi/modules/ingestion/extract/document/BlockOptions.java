/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

/**
 * What a unit's blocks should contain beyond its text.
 *
 * <p>
 * Text is all that is extracted so far, so there is nothing to choose yet: the
 * switches for images, tables and audio are added with the extraction of each,
 * and {@link #textOnly()} stays what it says.
 */
public record BlockOptions() {

    private static final BlockOptions TEXT_ONLY = new BlockOptions();

    /** Text blocks only. */
    public static BlockOptions textOnly() {
        return TEXT_ONLY;
    }
}
