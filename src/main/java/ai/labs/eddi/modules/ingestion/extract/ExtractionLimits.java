/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import java.time.Duration;

/**
 * What an extractor may spend on one file.
 *
 * <p>
 * Every limit here exists because the file is not the operator's: it arrives
 * from whoever uploads it, and the formats involved are containers that can
 * describe far more content than they occupy. A 5 MB spreadsheet can declare a
 * million rows, and a 1 MB archive can decompress to gigabytes.
 *
 * @param maxCharacters
 *            characters of Markdown to produce before stopping
 * @param maxParts
 *            pages, slides or sheets to read
 * @param maxRowsPerSheet
 *            rows per spreadsheet sheet
 * @param maxColumnsPerSheet
 *            columns per spreadsheet sheet — a sheet may declare cells out at
 *            column XFD whether or not anything was ever typed there
 * @param maxUncompressedBytes
 *            total bytes the text-bearing parts of an archive may decompress to
 *            — the zip-bomb bound, and also roughly the peak heap one
 *            extraction can take, since those parts are held in memory while
 *            they are read. For a PDF it bounds each compressed stream the same
 *            way, since the parser decodes a stream whole into memory
 * @param maxDuration
 *            wall-clock time one file may take. A PDF is the format that needs
 *            it: its page descriptions are programs, and a page can be written
 *            to keep the parser busy for as long as nobody stops it
 */
public record ExtractionLimits(int maxCharacters, int maxParts, int maxRowsPerSheet, int maxColumnsPerSheet,
        long maxUncompressedBytes, Duration maxDuration) {

    private static final long DEFAULT_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;

    private static final Duration DEFAULT_DURATION = Duration.ofSeconds(60);

    /**
     * Generous enough for a book chapter, small enough to survive a hostile file.
     */
    public static ExtractionLimits defaults() {
        return new ExtractionLimits(200_000, 500, 5_000, 64, DEFAULT_UNCOMPRESSED_BYTES, DEFAULT_DURATION);
    }

    /**
     * The limits without a time budget of their own, which then gets the default.
     */
    public ExtractionLimits(int maxCharacters, int maxParts, int maxRowsPerSheet, int maxColumnsPerSheet,
            long maxUncompressedBytes) {
        this(maxCharacters, maxParts, maxRowsPerSheet, maxColumnsPerSheet, maxUncompressedBytes, DEFAULT_DURATION);
    }

    public ExtractionLimits {
        maxCharacters = maxCharacters > 0 ? maxCharacters : 200_000;
        maxParts = maxParts > 0 ? maxParts : 500;
        maxRowsPerSheet = maxRowsPerSheet > 0 ? maxRowsPerSheet : 5_000;
        maxColumnsPerSheet = maxColumnsPerSheet > 0 ? maxColumnsPerSheet : 64;
        maxUncompressedBytes = maxUncompressedBytes > 0 ? maxUncompressedBytes : DEFAULT_UNCOMPRESSED_BYTES;
        maxDuration = maxDuration != null && !maxDuration.isNegative() && !maxDuration.isZero()
                ? maxDuration
                : DEFAULT_DURATION;
    }

    /**
     * The same limits with a different character cap, as a source's settings ask.
     */
    public ExtractionLimits withMaxCharacters(int characters) {
        return new ExtractionLimits(characters, maxParts, maxRowsPerSheet, maxColumnsPerSheet, maxUncompressedBytes,
                maxDuration);
    }

    /**
     * Everything one PDF may decode in total while it is read — every stream, every
     * reference to it, every stage of a filter chain. Twice the per-stream bound,
     * so one stream at the limit still leaves room for the fonts and pages around
     * it.
     */
    public long maxDecodedBytes() {
        return 2 * maxUncompressedBytes;
    }

    /** The same limits with a different time budget. */
    public ExtractionLimits withMaxDuration(Duration duration) {
        return new ExtractionLimits(maxCharacters, maxParts, maxRowsPerSheet, maxColumnsPerSheet,
                maxUncompressedBytes, duration);
    }
}
