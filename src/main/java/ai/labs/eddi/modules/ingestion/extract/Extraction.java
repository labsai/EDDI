/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

/** Small shared pieces every extractor needs. */
final class Extraction {

    private Extraction() {
    }

    /**
     * Trims to the character cap without splitting a surrogate pair — a lone half
     * is not valid text, and some embedding providers reject the whole request over
     * one.
     */
    static String capped(String text, int maxCharacters) {
        String trimmed = text.strip();
        if (trimmed.length() <= maxCharacters) {
            return trimmed;
        }
        int end = Character.isHighSurrogate(trimmed.charAt(maxCharacters - 1)) ? maxCharacters - 1 : maxCharacters;
        return trimmed.substring(0, end).strip();
    }

    /** Collapses the runs of whitespace that office formats produce in quantity. */
    static String normalize(String text) {
        return text == null ? "" : text.replace('\u00a0', ' ').replaceAll("[ \\t]+", " ").strip();
    }

    /**
     * Escapes what would otherwise be read as Markdown table syntax. A cell
     * containing a pipe shifts every later value under the wrong header.
     */
    static String escapeCell(String text) {
        return normalize(text).replace("|", "\\|").replace("\n", " ");
    }
}
