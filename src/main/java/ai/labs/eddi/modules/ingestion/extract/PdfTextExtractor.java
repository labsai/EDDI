/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/**
 * PDF, through PDFBox — already a dependency of this project.
 *
 * <p>
 * Page by page, so the character cap stops the work rather than only trimming
 * its result: a thousand-page manual should not be laid out in full to produce
 * the first two hundred thousand characters of it.
 */
@ApplicationScoped
public class PdfTextExtractor implements DocumentTextExtractor {

    private static final String MIME = "application/pdf";

    @Override
    public boolean supports(String mimeType) {
        return MIME.equalsIgnoreCase(mimeType);
    }

    @Override
    public String extract(byte[] content, ExtractionLimits limits) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                // PDFBox opens some encrypted files with an empty password and then
                // yields nothing useful. Saying so beats embedding a blank document.
                throw new UnreadableDocumentException(
                        "This PDF is encrypted. Upload a copy without a password.");
            }
            int pages = Math.min(document.getNumberOfPages(), limits.maxParts());
            StringBuilder markdown = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();

            for (int page = 1; page <= pages && markdown.length() < limits.maxCharacters(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).strip();
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
}
