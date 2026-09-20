/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.engine.attachments.MimeValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the extractor for an uploaded file, and says what the file actually is.
 *
 * <p>
 * What the file <em>is</em> is decided from its bytes, not from its name or the
 * MIME type the browser attached. Both of those are the uploader's word for it,
 * and .docx, .xlsx and .pptx are all ZIP archives, so neither distinguishes
 * them: a spreadsheet saved with a .docx name would otherwise be handed to the
 * Word extractor and refused as corrupt.
 */
@ApplicationScoped
public class DocumentExtractors {

    /**
     * File name endings the upload endpoint accepts, mapped to what that ending
     * claims the file is. The claim is only used for the text formats, which have
     * no signature to read — everything else is identified from its content.
     */
    private static final Map<String, String> MIME_BY_EXTENSION = mimeByExtension();

    /**
     * How far into a file to look for the bytes that mean it is not text. A NUL is
     * legal in no text format and common in every binary one.
     */
    private static final int BINARY_SNIFF_WINDOW = 8192;

    private final List<DocumentTextExtractor> extractors;

    @Inject
    public DocumentExtractors(Instance<DocumentTextExtractor> discovered) {
        this.extractors = discovered.stream().toList();
    }

    /** For tests and any caller assembling its own set. */
    public DocumentExtractors(List<DocumentTextExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    /** The extensions the Manager offers and the endpoint accepts, with the dot. */
    public static Set<String> supportedExtensions() {
        return MIME_BY_EXTENSION.keySet();
    }

    /**
     * What the content actually is.
     *
     * @param fileName
     *            the uploaded name, used only to tell one text format from another
     * @param content
     *            the whole file
     * @throws UnreadableDocumentException
     *             when nothing here can read it
     */
    public String resolveMimeType(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new UnreadableDocumentException("This file is empty.");
        }
        String detected = MimeValidator.detectMime(content);
        switch (detected) {
            case "application/pdf" -> {
                return "application/pdf";
            }
            case "application/zip" -> {
                String office = OpenXmlPackage.detectOfficeFormat(content);
                if (office == null) {
                    throw new UnreadableDocumentException(
                            "This is a ZIP archive rather than a document. Upload the files inside it instead.");
                }
                return office;
            }
            case "application/octet-stream" -> {
                return textMimeType(fileName, content);
            }
            default -> throw new UnreadableDocumentException(
                    "Files of type " + detected + " hold no text to ingest.");
        }
    }

    /**
     * Extracts the text, or explains why it could not.
     *
     * @throws UnreadableDocumentException
     *             when no extractor handles the type, or the file is damaged
     */
    public String extract(byte[] content, String mimeType, ExtractionLimits limits) {
        return extractorFor(mimeType)
                .orElseThrow(() -> new UnreadableDocumentException(
                        "Files of type " + mimeType + " cannot be ingested."))
                .extract(content, limits);
    }

    public Optional<DocumentTextExtractor> extractorFor(String mimeType) {
        return extractors.stream().filter(extractor -> extractor.supports(mimeType)).findFirst();
    }

    /**
     * A text format, chosen by extension. Content with no signature that is not
     * text either is refused here rather than embedded as mojibake.
     */
    private static String textMimeType(String fileName, byte[] content) {
        if (isLegacyOfficeFile(content)) {
            // The single most likely wrong upload, and the fix is one Save As away.
            throw new UnreadableDocumentException(
                    "This is an older Office file (.doc, .xls or .ppt). "
                            + "Save it as .docx, .xlsx or .pptx and upload it again.");
        }
        if (looksBinary(content)) {
            throw new UnreadableDocumentException(
                    "This file is not a document EDDI can read. Supported: "
                            + String.join(", ", supportedExtensions()) + ".");
        }
        String extension = extensionOf(fileName);
        String claimed = MIME_BY_EXTENSION.get(extension);
        // An unknown extension on readable text is still text: a .conf or a .rst is
        // worth ingesting, and refusing it would be pedantry about a file name.
        return claimed != null ? claimed : "text/plain";
    }

    /** The OLE2 compound-file signature that .doc, .xls and .ppt all start with. */
    private static boolean isLegacyOfficeFile(byte[] content) {
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};
        if (content.length < ole2.length) {
            return false;
        }
        for (int i = 0; i < ole2.length; i++) {
            if (content[i] != ole2[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksBinary(byte[] content) {
        int window = Math.min(content.length, BINARY_SNIFF_WINDOW);
        for (int i = 0; i < window; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        // Only the name: a directory component could carry a dot of its own.
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> mimeByExtension() {
        Map<String, String> byExtension = new LinkedHashMap<>();
        byExtension.put(".pdf", "application/pdf");
        byExtension.put(".docx", OpenXmlPackage.WORD_MIME);
        byExtension.put(".xlsx", OpenXmlPackage.EXCEL_MIME);
        byExtension.put(".pptx", OpenXmlPackage.POWERPOINT_MIME);
        byExtension.put(".txt", "text/plain");
        byExtension.put(".md", "text/markdown");
        byExtension.put(".markdown", "text/markdown");
        byExtension.put(".csv", "text/csv");
        byExtension.put(".tsv", "text/tab-separated-values");
        byExtension.put(".html", "text/html");
        byExtension.put(".htm", "text/html");
        byExtension.put(".json", "application/json");
        byExtension.put(".xml", "application/xml");
        byExtension.put(".yaml", "application/yaml");
        byExtension.put(".yml", "application/yaml");
        byExtension.put(".log", "text/plain");
        // Insertion order is kept: this list is shown to people, and it reads as a
        // sentence rather than a hash order.
        return Collections.unmodifiableMap(byExtension);
    }
}
