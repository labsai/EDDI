/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import jakarta.enterprise.context.ApplicationScoped;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String PRESENTATION = "ppt/presentation.xml";
    private static final String PRESENTATION_RELS = "ppt/_rels/presentation.xml.rels";

    @Override
    public boolean supports(String mimeType) {
        return MIME.equalsIgnoreCase(mimeType);
    }

    @Override
    public String extract(byte[] content, ExtractionLimits limits) {
        OpenXmlPackage archive = OpenXmlPackage.read(content, PowerPointTextExtractor::isWanted, limits);
        List<String> slides = slidesInDeckOrder(archive).stream().limit(limits.maxParts()).toList();
        if (slides.isEmpty()) {
            throw new UnreadableDocumentException("This file is not a PowerPoint presentation (it has no slides).");
        }

        StringBuilder markdown = new StringBuilder();
        int position = 0;
        for (String slide : slides) {
            position++;
            if (markdown.length() >= limits.maxCharacters()) {
                break;
            }
            String text = readSlide(archive.part(slide), limits);
            if (text.isEmpty()) {
                continue;
            }
            // The position in the deck, which is what somebody citing "slide 14"
            // means — not the number in the part name, which survives a reorder.
            markdown.append("## Slide ").append(position).append("\n\n").append(text).append("\n\n");
        }
        return Extraction.capped(markdown.toString(), limits.maxCharacters());
    }

    private static boolean isWanted(String name) {
        return PRESENTATION.equals(name) || PRESENTATION_RELS.equals(name)
                || SLIDE_PART.matcher(name).matches();
    }

    /**
     * The slides in the order the presentation lists them.
     *
     * <p>
     * Which is not the order of the part names: reordering a deck in PowerPoint
     * rewrites {@code sldIdLst} and leaves {@code slide7.xml} where it was. Reading
     * the numbers would hand back a deck whose slides are in the order they were
     * first created — subtly wrong, and wrong in a way nobody notices until a
     * retrieved passage cites the wrong slide.
     */
    private static List<String> slidesInDeckOrder(OpenXmlPackage archive) {
        List<String> ordered = new ArrayList<>();
        if (archive.has(PRESENTATION) && archive.has(PRESENTATION_RELS)) {
            Map<String, String> byRelationshipId = readRelationships(archive.part(PRESENTATION_RELS));
            for (String relationshipId : readSlideOrder(archive.part(PRESENTATION))) {
                String part = byRelationshipId.get(relationshipId);
                if (part != null && archive.has(part) && !ordered.contains(part)) {
                    ordered.add(part);
                }
            }
        }
        // Whatever the index did not account for, by part number: a generator that
        // writes no index, or a slide the index forgot, is still worth reading.
        archive.parts().keySet().stream()
                .filter(name -> SLIDE_PART.matcher(name).matches())
                .filter(name -> !ordered.contains(name))
                .sorted(Comparator.comparingInt(PowerPointTextExtractor::slideNumber))
                .forEach(ordered::add);
        return ordered;
    }

    /** The {@code r:id} of each slide, in deck order. */
    private static List<String> readSlideOrder(byte[] presentationXml) {
        List<String> relationshipIds = new ArrayList<>();
        try (OpenXmlPackage.Part reader = OpenXmlPackage.readerFor(presentationXml)) {
            XMLStreamReader xml = reader.reader();
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && "sldId".equals(xml.getLocalName())) {
                    String relationshipId = OpenXmlPackage.relationshipId(xml);
                    if (relationshipId != null) {
                        relationshipIds.add(relationshipId);
                    }
                }
            }
        } catch (XMLStreamException e) {
            // Falling back to part order beats refusing a deck we can read.
            return List.of();
        }
        return relationshipIds;
    }

    private static Map<String, String> readRelationships(byte[] relsXml) {
        Map<String, String> byId = new LinkedHashMap<>();
        try (OpenXmlPackage.Part reader = OpenXmlPackage.readerFor(relsXml)) {
            XMLStreamReader xml = reader.reader();
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(xml.getLocalName())) {
                    String id = OpenXmlPackage.attribute(xml, "Id");
                    String target = OpenXmlPackage.attribute(xml, "Target");
                    if (id != null && target != null) {
                        String trimmed = target.strip();
                        byId.put(id, trimmed.startsWith("/") ? trimmed.substring(1) : "ppt/" + trimmed);
                    }
                }
            }
        } catch (XMLStreamException e) {
            return Map.of();
        }
        return byId;
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
