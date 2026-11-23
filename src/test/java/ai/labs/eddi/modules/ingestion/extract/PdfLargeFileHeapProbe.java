/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.extract.document.DocumentManifest;
import ai.labs.eddi.modules.ingestion.extract.document.SplitConfig;

import java.nio.file.Path;

/**
 * Run in a child JVM with a small heap by {@link PdfLargeFileHeapTest}: builds
 * the manifest of a PDF file through the windowed path and prints one line,
 * {@code OK pages=<n> units=<k> tokens=<t>}, or exits non-zero.
 *
 * <p>
 * Arguments: the PDF, a scratch directory, pages per window.
 */
public final class PdfLargeFileHeapProbe {

    private PdfLargeFileHeapProbe() {
    }

    public static void main(String[] args) {
        Path pdf = Path.of(args[0]);
        Path scratch = Path.of(args[1]);
        int windowPages = Integer.parseInt(args[2]);
        // Every page measured, so every window is opened and read.
        var limits = new ExtractionLimits(200_000, 100_000, 5_000, 64, 64L * 1024 * 1024);
        var options = PdfWindowReader.Options.of(scratch, limits, null);
        DocumentManifest manifest = new PdfStructureExtractor().manifest(pdf, "probe", SplitConfig.defaults(), limits,
                options, windowPages);
        long tokens = manifest.leaves().stream().mapToLong(unit -> unit.estimatedTokens()).sum();
        System.out.println("OK pages=" + manifest.pageCount() + " units=" + manifest.units().size() + " tokens=" + tokens);
    }
}
