/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The one {@link PDFTextStripper} EDDI reads PDF text with: the ingestion
 * extractor, the attachment extractor and the structure extractor all use it,
 * so a bound added here holds for every path a PDF's text takes.
 *
 * <p>
 * Four bounds, each on a different way a page can take the process down:
 * <ul>
 * <li><b>Decode budget.</b> Before a page is parsed, its content is charged to
 * the {@link PdfDecodeBudget} armed on this thread
 * ({@link #chargePageContents}). A page outside the requested range is skipped
 * before it is charged.</li>
 * <li><b>Glyphs.</b> A page's glyphs are held until the page is written, so a
 * page claiming millions of them would fill the heap before any character cap
 * applied. Past the allowance the page in progress is written with what it laid
 * out, {@link #pageWasCut()} says so, and no later page of the same extraction
 * is read. The allowance is set at construction or per page with
 * {@link #allowGlyphs}.</li>
 * <li><b>Operators.</b> Every content-stream operator interpreted is counted,
 * form XObjects included however often they are referenced, against an optional
 * cap.</li>
 * <li><b>Time.</b> The deadline is checked every
 * {@value #OPERATORS_PER_DEADLINE_CHECK} operators, the thread's interrupt flag
 * on every one.</li>
 * </ul>
 *
 * <p>
 * Tripped, it stays tripped: PDFBox logs and swallows an {@link IOException}
 * thrown while it draws a form ({@code Do}), so every later operator refuses
 * again, and {@link #getText} refuses at the end if nothing else did. How it
 * refuses depends on the constructor: the {@link Instant} one throws an
 * {@link UnreadableDocumentException} saying the file took too long, which
 * PDFBox does not swallow; the others throw an {@link IOException} naming the
 * bound, for the attachment extractor to word.
 *
 * <p>
 * <b>Positions.</b> With a {@link LineListener} set, every laid-out line is
 * also reported with its position and font size. The listener only observes:
 * the text {@link #getText} returns is the same with or without one.
 */
public final class BoundedTextStripper extends PDFTextStripper {

    /** How many content-stream operators run between deadline checks. */
    static final int OPERATORS_PER_DEADLINE_CHECK = 256;

    /** What the ingestion path tells the user when a file runs out of time. */
    static final String TOO_LONG = "This PDF took too long to read. Split it into smaller files and upload those.";

    /**
     * One laid-out line of a page, as PDFBox groups it.
     */
    @FunctionalInterface
    public interface LineListener {
        /**
         * @param page
         *            1-based page number
         * @param text
         *            the line's text, its words joined as {@link #getText} joins them
         * @param x
         *            left edge of the first glyph, in points from the page's left
         * @param xEnd
         *            right edge of the last glyph
         * @param top
         *            the tallest glyph's top, in points from the page's top
         * @param fontSize
         *            the largest font size on the line, in points, scaled by the text
         *            matrix
         */
        void line(int page, String text, float x, float xEnd, float top, float fontSize);
    }

    private final long maxOperators;
    private final long deadlineNanos;
    private final Duration deadlineDescription;
    private final boolean unreadableOnRefusal;
    private long operators;
    private String tripped;
    private long glyphsLeft;
    private boolean pageWasCut;

    private LineListener listener;
    private final StringBuilder line = new StringBuilder();
    private float lineX;
    private float lineXEnd;
    private float lineTop;
    private float lineFontSize;
    private boolean lineStarted;

    /**
     * The ingestion bounds: no operator cap, the deadline the whole read shares, a
     * refusal that is an {@link UnreadableDocumentException}. Set the glyph
     * allowance with {@link #allowGlyphs} before each extraction.
     */
    public BoundedTextStripper(Instant deadline) {
        this(Long.MAX_VALUE, nanosUntil(deadline), null, Long.MAX_VALUE, true);
    }

    /** {@link #BoundedTextStripper(long, Duration, long)} with no glyph cap. */
    public BoundedTextStripper(long maxOperators, Duration deadline) {
        this(maxOperators, deadline, Long.MAX_VALUE);
    }

    /**
     * @param maxOperators
     *            content operators the whole extraction may interpret
     * @param deadline
     *            from now; the refusal names it in seconds
     * @param maxGlyphs
     *            glyphs the whole extraction may lay out
     */
    public BoundedTextStripper(long maxOperators, Duration deadline, long maxGlyphs) {
        this(maxOperators, System.nanoTime() + deadline.toNanos(), deadline, maxGlyphs, false);
    }

    private BoundedTextStripper(long maxOperators, long deadlineNanos, Duration deadlineDescription, long maxGlyphs,
            boolean unreadableOnRefusal) {
        this.maxOperators = maxOperators;
        this.deadlineNanos = deadlineNanos;
        this.deadlineDescription = deadlineDescription;
        this.glyphsLeft = maxGlyphs;
        this.unreadableOnRefusal = unreadableOnRefusal;
    }

    private static long nanosUntil(Instant deadline) {
        long now = System.nanoTime();
        try {
            return now + Duration.between(Instant.now(), deadline).toNanos();
        } catch (ArithmeticException e) {
            // A deadline centuries away: as good as none.
            return now + Long.MAX_VALUE / 2;
        }
    }

    /**
     * Sets the glyphs the next extraction may lay out, and clears
     * {@link #pageWasCut()}.
     */
    public void allowGlyphs(long glyphs) {
        this.glyphsLeft = glyphs;
        this.pageWasCut = false;
    }

    /** Whether a page was stopped at the glyph allowance. */
    public boolean pageWasCut() {
        return pageWasCut;
    }

    /** Reports every laid-out line to {@code listener}; null to stop. */
    public void setLineListener(LineListener listener) {
        this.listener = listener;
    }

    /**
     * A page stopped at its glyph allowance still lays out the glyphs it gave
     * before that: dropping them made a readable PDF whose first page is dense — a
     * small-font table, a large drawing — look like a scan with no text layer.
     *
     * <p>
     * {@link PDFTextStripper#processPage} is startPage, the page's program,
     * writePage, endPage; the program is what the glyph cap leaves, so the
     * remaining two are done here on what was collected.
     *
     * <p>
     * PDFTextStripper calls this for every page that has content, in or out of the
     * requested range, and lays out only those in range. A page outside it is
     * skipped before its content is decoded or charged: reading a document one page
     * at a time otherwise charged every page on every pass, and the square of the
     * page count refused ordinary long documents as decompression bombs.
     */
    @Override
    public void processPage(PDPage page) throws IOException {
        if (glyphsLeft < 0 || getCurrentPageNo() < getStartPage() || getCurrentPageNo() > getEndPage()) {
            return;
        }
        chargePageContents(page);
        try {
            super.processPage(page);
        } catch (GlyphsExhausted e) {
            pageWasCut = true;
            writePage();
            endPage(page);
        }
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        if (--glyphsLeft < 0) {
            throw new GlyphsExhausted();
        }
        super.processTextPosition(text);
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (tripped == null) {
            if (++operators > maxOperators) {
                tripped = "the PDF needs more than " + maxOperators + " content operators to extract";
            } else if (operators % OPERATORS_PER_DEADLINE_CHECK == 0 && System.nanoTime() - deadlineNanos > 0) {
                tripped = deadlineDescription == null
                        ? "text extraction ran past its deadline"
                        : "text extraction exceeded " + deadlineDescription.toSeconds() + " seconds";
            } else if (Thread.currentThread().isInterrupted()) {
                tripped = "text extraction was interrupted";
            }
        }
        if (tripped != null) {
            throw refusal();
        }
        super.processOperator(operator, operands);
    }

    @Override
    public String getText(PDDocument document) throws IOException {
        String text = super.getText(document);
        if (tripped != null) {
            throw refusal();
        }
        return text;
    }

    private IOException refusal() {
        if (unreadableOnRefusal) {
            throw new UnreadableDocumentException(TOO_LONG);
        }
        return new IOException("Refused: " + tripped);
    }

    // --- positions: observed, never changing the output

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        if (listener != null) {
            observe(text, textPositions);
        }
        super.writeString(text, textPositions);
    }

    @Override
    protected void writeWordSeparator() throws IOException {
        if (listener != null && lineStarted) {
            line.append(getWordSeparator());
        }
        super.writeWordSeparator();
    }

    @Override
    protected void writeLineSeparator() throws IOException {
        flushLine();
        super.writeLineSeparator();
    }

    @Override
    protected void writeParagraphEnd() throws IOException {
        flushLine();
        super.writeParagraphEnd();
    }

    @Override
    protected void writePageEnd() throws IOException {
        flushLine();
        super.writePageEnd();
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        flushLine();
        super.endPage(page);
    }

    private void observe(String text, List<TextPosition> positions) {
        line.append(text);
        for (TextPosition position : positions) {
            float x = position.getXDirAdj();
            float top = position.getYDirAdj() - position.getHeightDir();
            float right = x + position.getWidthDirAdj();
            if (!lineStarted) {
                lineStarted = true;
                lineX = x;
                lineXEnd = right;
                lineTop = top;
                lineFontSize = position.getFontSizeInPt();
                continue;
            }
            lineX = Math.min(lineX, x);
            lineXEnd = Math.max(lineXEnd, right);
            lineTop = Math.min(lineTop, top);
            lineFontSize = Math.max(lineFontSize, position.getFontSizeInPt());
        }
    }

    private void flushLine() {
        if (listener != null && lineStarted && !line.isEmpty()) {
            listener.line(getCurrentPageNo(), line.toString(), lineX, lineXEnd, lineTop, lineFontSize);
        }
        line.setLength(0);
        lineStarted = false;
    }

    // --- the decode budget

    /**
     * Charges the decode budget for the page content PDFBox reads around its
     * counted filters, before the page is parsed.
     *
     * <p>
     * Two paths bypass the counter. A page whose {@code /Contents} is one stream
     * filtered by exactly {@code /FlateDecode} is inflated by a decoder PDFBox
     * constructs itself, not one from the filter factory; that stream is decoded
     * once here through the counted filter, so its real size is known before the
     * parser is given it. And unfiltered content is never decoded at all, so one
     * unfiltered stream referenced thousands of times from a {@code /Contents}
     * array — each reference parsed in full, its operands retained until an
     * operator arrives — cost nothing; each reference is now charged its length.
     */
    public static void chargePageContents(PDPage page) throws IOException {
        COSBase contents = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
        if (contents instanceof COSStream stream) {
            if (COSName.FLATE_DECODE.equals(stream.getFilters())) {
                try (InputStream decoded = stream.createInputStream()) {
                    decoded.transferTo(OutputStream.nullOutputStream());
                }
            } else if (isUnfiltered(stream)) {
                PdfDecodeBudget.charge(stream.getLength());
            }
        } else if (contents instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.getObject(i) instanceof COSStream stream && isUnfiltered(stream)) {
                    PdfDecodeBudget.charge(stream.getLength());
                }
            }
        }
    }

    /**
     * Whether PDFBox reads the stream as it is stored: it takes a {@code /Filter}
     * that is neither a name nor a non-empty array ({@code []}, a number, a string,
     * a dictionary) as zero filters.
     */
    private static boolean isUnfiltered(COSStream stream) {
        COSBase filters = stream.getFilters();
        return !(filters instanceof COSName || (filters instanceof COSArray list && list.size() > 0));
    }

    /** The glyph allowance is spent. */
    private static final class GlyphsExhausted extends RuntimeException {
        GlyphsExhausted() {
            super(null, null, false, false);
        }
    }
}
