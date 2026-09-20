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
            while ((entry = zip.getNextEntry()) != null && parts.size() < partCeiling) {
                if (entry.isDirectory() || !wanted.test(entry.getName())) {
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
                byte[] buffer = new byte[COPY_BUFFER];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    budget -= read;
                    if (budget < 0) {
                        // The declared sizes in a ZIP header are not evidence; this is
                        // what was actually produced.
                        throw new UnreadableDocumentException(
                                "This file expands to more than " + (limits.maxUncompressedBytes() / (1024 * 1024))
                                        + " MB and was not read.");
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
     * Which Office format an archive actually is, from the parts it contains.
     *
     * <p>
     * The name and the declared MIME type are both the uploader's word for it, and
     * all three formats are ZIPs, so neither distinguishes them. The contents do: a
     * spreadsheet has an {@code xl/} tree whatever the file is called.
     *
     * @return the MIME type, or null when the archive is not an Office document
     */
    static String detectOfficeFormat(byte[] content) {
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
            }
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
