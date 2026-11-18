/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import jakarta.enterprise.context.ApplicationScoped;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Word (.docx): paragraphs, headings and list items.
 *
 * <p>
 * Headings are kept as Markdown headings rather than flattened, because the
 * chunker splits on them and a retrieved passage is far more useful when it
 * still says which section it came from.
 */
@ApplicationScoped
public class WordTextExtractor implements DocumentTextExtractor {

    private static final String MIME = OpenXmlPackage.WORD_MIME;
    private static final String DOCUMENT_PART = "word/document.xml";

    @Override
    public boolean supports(String mimeType) {
        return MIME.equalsIgnoreCase(mimeType);
    }

    @Override
    public String extract(byte[] content, ExtractionLimits limits) {
        OpenXmlPackage archive = OpenXmlPackage.read(content, DOCUMENT_PART::equals, limits);
        if (!archive.has(DOCUMENT_PART)) {
            throw new UnreadableDocumentException("This file is not a Word document (no word/document.xml).");
        }

        StringBuilder markdown = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        String pendingStyle = null;
        boolean inNumbering = false;
        boolean visibleOnly = limits.visibleTextOnly();
        // Text of a run marked hidden (w:vanish) or set at one point or less is what a
        // reader never sees, and what a prompt-injection carrier is made of.
        boolean runHidden = false;
        boolean inParagraphProperties = false;

        try (OpenXmlPackage.Part reader = OpenXmlPackage.readerFor(archive.part(DOCUMENT_PART))) {
            XMLStreamReader xml = reader.reader();
            while (xml.hasNext() && markdown.length() < limits.maxCharacters()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "t" -> {
                            // Consumed either way, so the reader stays in step.
                            String text = xml.getElementText();
                            if (!runHidden) {
                                paragraph.append(text);
                            }
                        }
                        case "r" -> runHidden = false;
                        case "pPr" -> inParagraphProperties = true;
                        case "vanish" -> {
                            // In a paragraph's own properties it hides the paragraph mark, not
                            // text.
                            if (visibleOnly && !inParagraphProperties && !isOff(OpenXmlPackage.attribute(xml, "val"))) {
                                runHidden = true;
                            }
                        }
                        case "sz" -> {
                            if (visibleOnly && !inParagraphProperties && isTiny(OpenXmlPackage.attribute(xml, "val"))) {
                                runHidden = true;
                            }
                        }
                        case "tab" -> paragraph.append(' ');
                        case "br" -> paragraph.append('\n');
                        case "pStyle" -> pendingStyle = OpenXmlPackage.attribute(xml, "val");
                        case "numPr" -> inNumbering = true;
                        default -> {
                            // Everything else is layout.
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "pPr".equals(xml.getLocalName())) {
                    inParagraphProperties = false;
                } else if (event == XMLStreamConstants.END_ELEMENT && "r".equals(xml.getLocalName())) {
                    runHidden = false;
                } else if (event == XMLStreamConstants.END_ELEMENT && "p".equals(xml.getLocalName())) {
                    appendParagraph(markdown, paragraph.toString(), pendingStyle, inNumbering);
                    paragraph.setLength(0);
                    pendingStyle = null;
                    inNumbering = false;
                }
            }
        } catch (XMLStreamException e) {
            throw new UnreadableDocumentException("This Word document's contents could not be read.", e);
        }
        appendParagraph(markdown, paragraph.toString(), pendingStyle, inNumbering);
        return Extraction.capped(markdown.toString(), limits.maxCharacters());
    }

    /**
     * A toggle property such as {@code w:vanish}: present means on, unless it says
     * it is off.
     */
    private static boolean isOff(String value) {
        return value != null && (value.equals("0") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off"));
    }

    /**
     * {@code w:sz} is in half-points; two is one point, which no reader can read.
     */
    private static boolean isTiny(String halfPoints) {
        if (halfPoints == null) {
            return false;
        }
        try {
            int size = Integer.parseInt(halfPoints.trim());
            return size > 0 && size <= 2;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void appendParagraph(StringBuilder markdown, String text, String style, boolean numbered) {
        String content = Extraction.normalize(text);
        if (content.isEmpty()) {
            return;
        }
        int headingLevel = headingLevel(style);
        if (headingLevel > 0) {
            markdown.append("#".repeat(headingLevel)).append(' ').append(content).append("\n\n");
        } else if (numbered) {
            markdown.append("- ").append(content).append('\n');
        } else {
            markdown.append(content).append("\n\n");
        }
    }

    /**
     * {@code Heading1}, {@code heading 2}, {@code Title} — Word writes all of
     * these.
     */
    private static int headingLevel(String style) {
        if (style == null) {
            return 0;
        }
        String normalized = style.toLowerCase().replace(" ", "");
        if (normalized.equals("title")) {
            return 1;
        }
        if (!normalized.startsWith("heading")) {
            return 0;
        }
        try {
            return Math.min(6, Math.max(1, Integer.parseInt(normalized.substring("heading".length()))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
