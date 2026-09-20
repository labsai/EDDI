/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import jakarta.enterprise.context.ApplicationScoped;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PowerPoint (.pptx): one Markdown section per slide, in slide order.
 *
 * <p>
 * The slide number is written into the text because a deck is usually cited by
 * it ("slide 14 says…"), and a retrieved chunk that has lost it cannot be
 * checked against the original.
 */
@ApplicationScoped
public class PowerPointTextExtractor implements DocumentTextExtractor {

    private static final String MIME = OpenXmlPackage.POWERPOINT_MIME;
    /** Bounded digits so the number always parses and the match stays linear. */
    private static final Pattern SLIDE_PART = Pattern.compile("ppt/slides/slide(\\d{1,9})\\.xml");

    @Override
    public boolean supports(String mimeType) {
        return MIME.equalsIgnoreCase(mimeType);
    }

    @Override
    public String extract(byte[] content, ExtractionLimits limits) {
        OpenXmlPackage archive = OpenXmlPackage.read(content, name -> SLIDE_PART.matcher(name).matches(), limits);
        // ZIP entry order says nothing about slide order; the number in the part
        // name is what the presentation itself uses.
        List<String> slides = archive.parts().keySet().stream()
                .sorted(Comparator.comparingInt(PowerPointTextExtractor::slideNumber))
                .limit(limits.maxParts())
                .toList();
        if (slides.isEmpty()) {
            throw new UnreadableDocumentException("This file is not a PowerPoint presentation (it has no slides).");
        }

        StringBuilder markdown = new StringBuilder();
        for (String slide : slides) {
            if (markdown.length() >= limits.maxCharacters()) {
                break;
            }
            String text = readSlide(archive.part(slide), limits);
            if (text.isEmpty()) {
                continue;
            }
            markdown.append("## Slide ").append(slideNumber(slide)).append("\n\n").append(text).append("\n\n");
        }
        return Extraction.capped(markdown.toString(), limits.maxCharacters());
    }

    private static String readSlide(byte[] slideXml, ExtractionLimits limits) {
        StringBuilder text = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        try (OpenXmlPackage.Part reader = OpenXmlPackage.readerFor(slideXml)) {
            XMLStreamReader xml = reader.reader();
            while (xml.hasNext() && text.length() < limits.maxCharacters()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "t" -> paragraph.append(xml.getElementText());
                        case "br" -> paragraph.append(' ');
                        default -> {
                            // Shapes, transitions, animation timings.
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "p".equals(xml.getLocalName())) {
                    appendLine(text, paragraph.toString());
                    paragraph.setLength(0);
                }
            }
        } catch (XMLStreamException e) {
            throw new UnreadableDocumentException("A slide in this presentation could not be read.", e);
        }
        appendLine(text, paragraph.toString());
        return text.toString().strip();
    }

    private static void appendLine(StringBuilder text, String line) {
        String content = Extraction.normalize(line);
        if (!content.isEmpty()) {
            text.append(content).append('\n');
        }
    }

    private static int slideNumber(String partName) {
        Matcher matcher = SLIDE_PART.matcher(partName);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
}
