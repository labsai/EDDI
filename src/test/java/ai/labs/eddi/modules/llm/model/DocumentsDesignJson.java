/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import java.util.List;

/**
 * The agent-level {@code documents} JSON of the design (the PDF documents
 * design, §14 "configuration"; its shape is also documented in
 * {@code docs/langchain.md}, "Documents"), written out literally — <b>not</b>
 * derived from {@link DocumentsConfig} — so that a key missing from the class,
 * or a default that drifts, fails a test instead of being agreed with.
 * {@code "bridge": null} is the design's value (off); {@link #BRIDGE_SET} is
 * the one block that has no fields yet.
 */
public final class DocumentsDesignJson {

    private DocumentsDesignJson() {
    }

    /** The design's JSON, every key at its default. */
    public static final String FULL = """
            {
              "structure": "OFF",
              "split":   { "strategy": "AUTO", "pagesPerUnit": 10, "maxUnitTokens": 12000, "minUnitTokens": 500, "maxUnits": 60 },
              "unitForm": "AUTO",
              "images":  { "mode": "EMBEDDED", "pageRender": "NEVER", "maxPerUnit": 20, "maxEdgePx": 1568, "minEdgePx": 48, "renderDpi": 110 },
              "tables":  { "image": "NEVER", "format": "MARKDOWN", "maxInlineRows": 60 },
              "audio":   { "extract": false, "maxFiles": 10, "maxPerUnit": 2, "maxDurationSeconds": 600 },
              "embeddedFiles": { "extract": "NONE", "mimeTypes": [] },
              "derived": { "maxPerSource": 200, "maxBytesPerSource": 52428800 },
              "sharing": { "delegation": false, "handoff": false, "maxFilesPerShare": 5, "maxSharesPerTurn": 10, "maxDepth": 3 },
              "library": { "enabled": false, "promote": "TOOL", "scopes": ["self"], "teams": [], "memoryCard": true,
                           "maxCardsRecalled": 5, "retentionDays": null },
              "index":   { "rag": null },
              "large":   { "thresholdPages": 80, "thresholdBytes": 10485760, "processOnUpload": false, "onReady": "NOTE",
                           "summary": { "llm": null, "leafOutputTokens": 300, "reduceFanIn": 12, "maxLevels": 4,
                                        "maxParallelUnits": 4, "maxCostPerDocument": 5.00, "images": "CAPTIONS" } },
              "bridge":  null
            }
            """;

    /** The bridge block, which the design names but does not yet give fields. */
    public static final String BRIDGE_SET = "{\"bridge\": {}}";

    /**
     * Every leaf key of {@link #FULL} that carries a value (the three design
     * {@code null}s — {@code library.retentionDays}, {@code index.rag},
     * {@code large.summary.llm} — are listed in {@link #NULL_IN_DESIGN}), plus
     * {@code bridge}.
     */
    public static final List<String> LEAF_KEYS = List.of("structure", "split.strategy", "split.pagesPerUnit", "split.maxUnitTokens",
            "split.minUnitTokens", "split.maxUnits", "unitForm", "images.mode", "images.pageRender", "images.maxPerUnit",
            "images.maxEdgePx", "images.minEdgePx", "images.renderDpi", "tables.image", "tables.format", "tables.maxInlineRows",
            "audio.extract", "audio.maxFiles", "audio.maxPerUnit", "audio.maxDurationSeconds", "embeddedFiles.extract",
            "embeddedFiles.mimeTypes", "derived.maxPerSource", "derived.maxBytesPerSource", "sharing.delegation", "sharing.handoff",
            "sharing.maxFilesPerShare", "sharing.maxSharesPerTurn", "sharing.maxDepth", "library.enabled", "library.promote",
            "library.scopes", "library.teams", "library.memoryCard", "library.maxCardsRecalled", "large.thresholdPages",
            "large.thresholdBytes", "large.processOnUpload", "large.onReady", "large.summary.leafOutputTokens",
            "large.summary.reduceFanIn", "large.summary.maxLevels", "large.summary.maxParallelUnits",
            "large.summary.maxCostPerDocument", "large.summary.images");

    /**
     * Keys whose design default is {@code null}, with a sample value to set them
     * to.
     */
    public static final List<List<String>> NULL_IN_DESIGN = List.of(List.of("library.retentionDays", "30"),
            List.of("index.rag", "\"eddi://ai.labs.rag/ragstore/rags/aaaaaaaaaaaaaaaaaaaaaaaa?version=1\""),
            List.of("large.summary.llm", "\"eddi://ai.labs.llm/llmstore/llms/aaaaaaaaaaaaaaaaaaaaaaaa?version=1\""));

    /**
     * A {@code documents} JSON that sets exactly one key, {@code path}, to the raw
     * JSON {@code value}.
     */
    public static String withOnly(String path, String value) {
        String[] parts = path.split("\\.");
        StringBuilder json = new StringBuilder();
        for (String part : parts) {
            json.append("{\"").append(part).append("\":");
        }
        json.append(value);
        json.append("}".repeat(parts.length));
        return json.toString();
    }
}
