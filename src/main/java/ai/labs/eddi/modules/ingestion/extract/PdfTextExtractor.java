/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * PDF, through PDFBox — already a dependency of this project.
 *
 * <p>
 * Page by page, so the character cap stops the work rather than only trimming
 * its result: a thousand-page manual should not be laid out in full to produce
 * the first two hundred thousand characters of it.
 *
 * <h2>Bounded, because the file is not the operator's</h2>
 * <p>
 * PDFBox reads whatever it is given with no limit of its own, and a PDF is a
 * container of compressed streams and of page descriptions that are small
 * programs. Three bounds, each on a different way a file can take the process
 * down with it:
 * <ul>
 * <li><b>Memory, per stream.</b> {@link PdfStreamBudget} measures what every
 * compressed stream expands to before the file is opened, because PDFBox
 * decodes a stream whole into memory and a deflate bomb is a few kilobytes that
 * expand to gigabytes. The parser's own scratch space is capped at the same
 * figure.</li>
 * <li><b>Memory, per page.</b> Text is laid out a page at a time and a page's
 * glyphs are held until it is done. A page may claim millions of them, so the
 * count is capped a little above what the character budget could ever
 * keep.</li>
 * <li><b>Time.</b> The page's program is checked against a deadline every few
 * hundred operators, so a page written to loop the parser ends with a message
 * rather than holding the ingestion worker indefinitely.</li>
 * </ul>
 */
@ApplicationScoped
public class PdfTextExtractor implements DocumentTextExtractor {

    private static final String MIME = "application/pdf";

    /** How many content-stream operators run between deadline checks. */
    private static final int OPERATORS_PER_DEADLINE_CHECK = 256;

    /**
     * Glyphs one page may lay out beyond the characters still wanted. Spacing and
     * line breaks make the text a little longer than the glyphs, and a page is kept
     * whole, so the slack is generous; what it bounds is a page that claims a
     * million glyphs.
     */
    private static final int GLYPH_SLACK = 20_000;

    @Override
    public boolean supports(String mimeType) {
        return MIME.equalsIgnoreCase(mimeType);
    }

    @Override
    public String extract(byte[] content, ExtractionLimits limits) {
        Instant deadline = Instant.now().plus(limits.maxDuration());
        PdfStreamBudget.requireWithin(content, limits.maxUncompressedBytes(), deadline);

        try (PDDocument document = Loader.loadPDF(content, "", null, null,
                MemoryUsageSetting.setupMainMemoryOnly(limits.maxUncompressedBytes()).streamCache)) {
            if (document.isEncrypted()) {
                // PDFBox opens some encrypted files with an empty password and then
                // yields nothing useful. Saying so beats embedding a blank document.
                throw new UnreadableDocumentException(
                        "This PDF is encrypted. Upload a copy without a password.");
            }
            int pages = Math.min(document.getNumberOfPages(), limits.maxParts());
            StringBuilder markdown = new StringBuilder();
            BoundedTextStripper stripper = new BoundedTextStripper(deadline);

            for (int page = 1; page <= pages && markdown.length() < limits.maxCharacters(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.allowGlyphs(limits.maxCharacters() - markdown.length() + GLYPH_SLACK);
                String text;
                try {
                    text = stripper.getText(document).strip();
                } catch (PageTooLong e) {
                    // Everything this page could contribute is past the character cap
                    // anyway. Stop here with what the earlier pages gave.
                    break;
                }
                if (text.isEmpty()) {
                    // A scanned page with no text layer. Skipped silently: a document
                    // of them produces nothing, which the caller reports as empty.
                    continue;
                }
                if (!markdown.isEmpty()) {
                    markdown.append("\n\n");
                }
                markdown.append(text);
            }
            return Extraction.capped(markdown.toString(), limits.maxCharacters());
        } catch (InvalidPasswordException e) {
            // The other way PDFBox refuses an encrypted file. Without this the
            // operator is told their working PDF is corrupt, and the one thing they
            // could do about it — save a copy without the password — goes unsaid.
            throw new UnreadableDocumentException(
                    "This PDF is encrypted. Upload a copy without a password.", e);
        } catch (UnreadableDocumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UnreadableDocumentException("This file could not be read as a PDF.", e);
        }
    }

    /** Stops a page that runs too long or lays out too much. */
    private static final class BoundedTextStripper extends PDFTextStripper {

        private final Instant deadline;
        private long glyphsLeft;
        private int operatorsSinceCheck;

        BoundedTextStripper(Instant deadline) {
            this.deadline = deadline;
        }

        void allowGlyphs(long glyphs) {
            this.glyphsLeft = glyphs;
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if (++operatorsSinceCheck >= OPERATORS_PER_DEADLINE_CHECK) {
                operatorsSinceCheck = 0;
                if (Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted()) {
                    throw new UnreadableDocumentException(
                            "This PDF took too long to read. Split it into smaller files and upload those.");
                }
            }
            super.processOperator(operator, operands);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (--glyphsLeft < 0) {
                throw new PageTooLong();
            }
            super.processTextPosition(text);
        }
    }

    /** A page that would lay out more glyphs than could ever be kept. */
    private static final class PageTooLong extends RuntimeException {
        PageTooLong() {
            super(null, null, false, false);
        }
    }
}
