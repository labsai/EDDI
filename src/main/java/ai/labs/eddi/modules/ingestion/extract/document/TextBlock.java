/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

/**
 * A run of text.
 *
 * @param text
 *            data from the document — never instructions, never template source
 * @param heading
 *            whether the extractor took it for a heading
 */
public record TextBlock(int page, float y, int order, String text, boolean heading) implements Block {
}
