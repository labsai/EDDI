/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The structure of one document: its units and what the extractor noticed,
 * never its bytes or its text.
 *
 * @param rootRef
 *            the storage reference of the uploaded file this describes
 * @param sha256
 *            lower-case hex SHA-256 of the file's bytes
 * @param pageCount
 *            the document's page count
 * @param splitUsed
 *            the strategy that produced the units —
 *            {@link SplitStrategy#OUTLINE} or {@link SplitStrategy#PAGES},
 *            whatever was asked for
 * @param units
 *            parents before their children, in document order
 * @param embeddedFiles
 *            files embedded in the document; empty until those are read
 * @param warnings
 *            what a reader of the manifest should know, in plain language.
 *            Never text taken from the document
 */
public record DocumentManifest(String rootRef, String sha256, int pageCount, SplitStrategy splitUsed,
        List<DocumentUnit> units, List<EmbeddedFileInfo> embeddedFiles, List<String> warnings) {

    public DocumentManifest {
        units = units == null ? List.of() : List.copyOf(units);
        embeddedFiles = embeddedFiles == null ? List.of() : List.copyOf(embeddedFiles);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * The units no other unit is part of: together they cover every page once, in
     * document order.
     */
    public List<DocumentUnit> leaves() {
        Set<String> parents = units.stream().map(DocumentUnit::parentId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return units.stream().filter(unit -> !parents.contains(unit.id())).toList();
    }
}
