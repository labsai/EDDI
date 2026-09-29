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
import org.apache.pdfbox.pdmodel.PDPage;
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
 * count is capped a little above what the character budget could ever keep; a
 * page stopped there still gives the text it laid out.</li>
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
        return extract(content, limits, true);
    }

    /**
     * @param preFilter
     *            whether to run the raw-byte scan first. Off only in tests that
     *            show the decode budget holds on its own, for every case the scan
     *            might miss
     */
    String extract(byte[] content, ExtractionLimits limits, boolean preFilter) {
        Instant deadline = Instant.now().plus(limits.maxDuration());
        if (preFilter) {
            // A cheap first look at the raw bytes, refusing the obvious bomb before
            // PDFBox allocates anything. Not the bound: the budget below is.
            PdfStreamBudget.requireWithin(content, limits.maxUncompressedBytes(), deadline);
        }

        try (var budget = PdfDecodeBudget.open(limits.maxDecodedBytes())) {
            String text;
            try {
                text = read(content, limits, deadline);
            } catch (UnreadableDocumentException e) {
                if (budget.exceeded()) {
                    throw tooMuchToDecode(budget);
                }
                throw e;
            }
            // Checked on success too: PDFBox catches some failures and carries on,
            // and a document that passed its budget is refused however it ended.
            if (budget.exceeded()) {
                throw tooMuchToDecode(budget);
            }
            return text;
        }
    }

    private static UnreadableDocumentException tooMuchToDecode(PdfDecodeBudget.Budget budget) {
        return new UnreadableDocumentException("This PDF expands to more than " + budget.max() / (1024 * 1024)
                + " MB while it is read, which is more than one document may take. If the file is genuine, "
                + "split it, or save a copy without embedded attachments.");
    }

    private String read(byte[] content, ExtractionLimits limits, Instant deadline) {
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
                String text = stripper.getText(document).strip();
                if (!text.isEmpty()) {
                    if (!markdown.isEmpty()) {
                        markdown.append("\n\n");
                    }
                    markdown.append(text);
                }
                // An empty page is a scanned one with no text layer, skipped silently:
                // a document of them produces nothing, which the caller reports as empty.
                if (stripper.pageWasCut()) {
                    // The page held more glyphs than the character cap could keep. What
                    // it laid out before it was stopped is kept above; no later page is
                    // read.
                    break;
                }
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
        private boolean pageWasCut;
        private int operatorsSinceCheck;

        BoundedTextStripper(Instant deadline) {
            this.deadline = deadline;
        }

        void allowGlyphs(long glyphs) {
            this.glyphsLeft = glyphs;
            this.pageWasCut = false;
        }

        /** Whether the last page was stopped at its glyph allowance. */
        boolean pageWasCut() {
            return pageWasCut;
        }

        /**
         * A page stopped at its glyph allowance still lays out the glyphs it gave
         * before that. Dropping them made a readable PDF whose first page is dense — a
         * small-font table, a large drawing — look like a scan with no text layer,
         * since the upload probe allows only a few dozen characters.
         *
         * <p>
         * {@link PDFTextStripper#processPage} is startPage, the page's program,
         * writePage, endPage; the program is what {@link PageTooLong} leaves, so the
         * remaining two are done here on what was collected.
         */
        @Override
        public void processPage(PDPage page) throws IOException {
            try {
                super.processPage(page);
            } catch (PageTooLong e) {
                pageWasCut = true;
                writePage();
                endPage(page);
            }
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
