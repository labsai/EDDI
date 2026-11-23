/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.time.Instant;

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
 * <li><b>Memory, per stream and per document.</b> {@link PdfStreamBudget}
 * measures what every compressed stream expands to before the file is opened,
 * because PDFBox decodes a stream whole into memory and a deflate bomb is a few
 * kilobytes that expand to gigabytes. That scan is a pre-filter; the bound is
 * {@link PdfDecodeBudget}, which counts everything PDFBox decodes while the
 * document is read (twice {@code maxUncompressedBytes} in total). The parser's
 * own scratch space is capped at {@code maxUncompressedBytes}.</li>
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
}
