/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The parts of a .docx, .xlsx or .pptx that carry text.
 *
 * <p>
 * All three are ZIP archives of XML, which the JDK reads on its own. Apache POI
 * would do this too, and much more — at seven extra jars and about 14 MB, built
 * on reflection and with a long history of parser CVEs. For pulling text out of
 * three known parts, the JDK's own reader is the smaller surface and the one
 * whose limits this class can state explicitly.
 *
 * <p>
 * Every bound here is load-bearing, because the file comes from whoever
 * uploaded it: a small archive can declare enormous entries (a zip bomb), an
 * entry can be repeated, and an XML document can expand entities into
 * gigabytes. Reading is capped by total decompressed bytes, entity expansion is
 * off, and external entities and DTDs are refused outright.
 */
final class OpenXmlPackage {

    private static final int COPY_BUFFER = 8192;

    /**
     * Headroom over {@code maxParts} for the parts that are not pages: a workbook
     * index, a shared-string table, a presentation's slide order.
     */
    private static final int AUXILIARY_PARTS = 16;

    /**
     * How far into an archive to look for the part that names its format. The
     * marker is within the first handful of entries in every file these formats
     * produce; a file that hides it past this point is not one of them.
     */
    private static final int MAX_ENTRIES_SNIFFED = 64;

    static final String WORD_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String EXCEL_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String POWERPOINT_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private final Map<String, byte[]> parts;

    private OpenXmlPackage(Map<String, byte[]> parts) {
        this.parts = parts;
    }

    /**
     * Reads the parts whose names the predicate accepts.
     *
     * @throws UnreadableDocumentException
     *             when the bytes are not a readable archive, or the archive
     *             decompresses past the limit
     */
    static OpenXmlPackage read(byte[] content, Predicate<String> wanted, ExtractionLimits limits) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        long budget = limits.maxUncompressedBytes();
        int partCeiling = limits.maxParts() + AUXILIARY_PARTS;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            byte[] buffer = new byte[COPY_BUFFER];
            while ((entry = zip.getNextEntry()) != null && parts.size() < partCeiling) {
                if (entry.isDirectory() || !wanted.test(entry.getName())) {
                    // Drained through the same accounting rather than skipped. Moving
                    // to the next entry inflates the rest of this one anyway, so a
                    // file whose first entry deflates a thousand to one would cost
                    // gigabytes of CPU per read with the budget untouched — an entry
                    // nobody wanted was the cheapest place to hide a zip bomb.
                    budget = drain(zip, buffer, budget, limits);
                    continue;
                }
                if (parts.containsKey(entry.getName())) {
                    // A real Office file names each part once. Two entries under one
                    // name means two readers can disagree about the contents, which is
                    // how a file gets past review saying one thing and ingests another.
                    throw new UnreadableDocumentException(
                            "This file contains the same part twice (" + entry.getName() + ") and was not read.");
                }
                ByteArrayOutputStream part = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    budget -= read;
                    if (budget < 0) {
                        throw tooLarge(limits);
                    }
                    part.write(buffer, 0, read);
                }
                parts.put(entry.getName(), part.toByteArray());
            }
        } catch (UnreadableDocumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UnreadableDocumentException("This file could not be read as an Office document.", e);
        }
        return new OpenXmlPackage(parts);
    }

    /**
     * Reads an entry to its end without keeping it, charging what it produced to
     * the budget.
     *
     * <p>
     * The declared sizes in a ZIP header are not evidence of anything; this is what
     * the entry actually decompressed to.
     */
    private static long drain(ZipInputStream zip, byte[] buffer, long budget, ExtractionLimits limits)
            throws IOException {

        long remaining = budget;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            remaining -= read;
            if (remaining < 0) {
                throw tooLarge(limits);
            }
        }
        return remaining;
    }

    private static UnreadableDocumentException tooLarge(ExtractionLimits limits) {
        return new UnreadableDocumentException("This file expands to more than "
                + (limits.maxUncompressedBytes() / (1024 * 1024)) + " MB and was not read.");
    }

    /**
     * Which Office format an archive actually is, from the parts it contains.
     *
     * <p>
     * The name and the declared MIME type are both the uploader's word for it, and
     * all three formats are ZIPs, so neither distinguishes them. The contents do: a
     * spreadsheet has an {@code xl/} tree whatever the file is called.
     *
     * @return the MIME type, or null when the archive is not an Office document
     */
    static String detectOfficeFormat(byte[] content, ExtractionLimits limits) {
        // Entry names only, and each entry is skipped without being read: moving to
        // the next one still inflates the current one, so this runs under the same
        // budget the full read does. It happens inside an upload request, which is
        // exactly where an unbounded inflate hurts most.
        long budget = limits.maxUncompressedBytes();
        byte[] buffer = new byte[COPY_BUFFER];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int inspected = 0;
            while ((entry = zip.getNextEntry()) != null && inspected++ < MAX_ENTRIES_SNIFFED) {
                String name = entry.getName();
                if (name.startsWith("word/")) {
                    return WORD_MIME;
                }
                if (name.startsWith("xl/")) {
                    return EXCEL_MIME;
                }
                if (name.startsWith("ppt/")) {
                    return POWERPOINT_MIME;
                }
                budget = drain(zip, buffer, budget, limits);
            }
        } catch (UnreadableDocumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    boolean has(String part) {
        return parts.containsKey(part);
    }

    byte[] part(String name) {
        return parts.get(name);
    }

    Map<String, byte[]> parts() {
        return parts;
    }

    /**
     * A reader for one part, with entity expansion and DTDs disabled — an Office
     * file is third-party XML, and the billion-laughs attack is older than the
     * format.
     */
    static Part readerFor(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        InputStream in = new ByteArrayInputStream(xml);
        return new Part(factory.createXMLStreamReader(in));
    }

    /** {@link XMLStreamReader} predates try-with-resources; this closes it. */
    record Part(XMLStreamReader reader) implements AutoCloseable {
        @Override
        public void close() throws XMLStreamException {
            reader.close();
        }
    }

    /**
     * The value of the {@code r:id} attribute — the relationship this element
     * points at.
     *
     * <p>
     * Not {@link #attribute}: {@code <p:sldId id="256" r:id="rId2"/>} carries two
     * attributes whose local name is {@code id}, and taking the first gives the
     * slide's own number instead of the relationship. That reads as a deck whose
     * index points nowhere, so the index is ignored and the slides come back in
     * part-name order — the exact thing reading the index was for.
     */
    static String relationshipId(XMLStreamReader xml) {
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            String namespace = xml.getAttributeNamespace(i);
            // Namespaced, and non-empty with it: a reader may report an unprefixed
            // attribute's namespace as "" rather than null, and taking that as
            // "namespaced" puts us straight back on the plain `id`.
            if ("id".equals(xml.getAttributeLocalName(i)) && namespace != null && !namespace.isEmpty()) {
                return xml.getAttributeValue(i);
            }
        }
        return null;
    }

    /** The attribute's value regardless of which namespace prefix wrote it. */
    static String attribute(XMLStreamReader xml, String name) {
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            if (name.equals(xml.getAttributeLocalName(i))) {
                return xml.getAttributeValue(i);
            }
        }
        return null;
    }
}
