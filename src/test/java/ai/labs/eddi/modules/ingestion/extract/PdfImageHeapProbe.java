/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.extract.PdfImageCollector.Collection;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.time.Instant;

/**
 * Run by {@link PdfImageBombTest} in a child JVM with a small heap: prints one
 * {@code OK …} line when the work completed, and exits non-zero otherwise (an
 * {@link OutOfMemoryError} included).
 */
public final class PdfImageHeapProbe {

    /**
     * 10,000 distinct 64 × 64 images, 1,000 per page: collected, then each
     * normalized.
     */
    static final String MANY_IMAGES = "many-images";

    /** One page opening two million graphics states before it draws an image. */
    static final String NESTED_SAVES = "nested-saves";

    private PdfImageHeapProbe() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case MANY_IMAGES -> manyImages();
            case NESTED_SAVES -> nestedSaves();
            default -> {
                System.out.println("unknown mode " + mode);
                System.exit(2);
            }
        }
    }

    private static void manyImages() throws Exception {
        byte[] pdf = PdfFixtures.imageBombMany(10_000, 1_000, 64);
        var normalizer = new ImageNormalizer();
        Instant deadline = Instant.now().plusSeconds(150);
        int encoded = 0;
        // 10,000 × 12 KB of decoded samples: more than the default budget, so this
        // read gets a larger one; the heap, not the budget, is what is under test
        try (PDDocument document = Loader.loadPDF(pdf); var budget = PdfDecodeBudget.open(1L << 30)) {
            Collection collection = PdfImageCollector.collect(document, 1, document.getNumberOfPages(), null,
                    deadline);
            if (collection.truncated()) {
                System.out.println("truncated");
                System.exit(3);
            }
            // Every placement is held at once, as a caller ordering blocks would hold them
            for (var placement : collection.images()) {
                if (normalizer.normalize(placement, null, deadline) instanceof ImageNormalizer.Encoded) {
                    encoded++;
                }
            }
        }
        System.out.println("OK " + encoded + " encoded");
    }

    private static void nestedSaves() throws Exception {
        byte[] pdf = PdfFixtures.nestedSaves(2_000_000);
        try (PDDocument document = Loader.loadPDF(pdf); var budget = PdfDecodeBudget.open(1L << 30)) {
            Collection collection = PdfImageCollector.collect(document, 1, 1, null, Instant.now().plusSeconds(150));
            System.out.println("OK " + collection.images().size() + " placed");
        }
    }
}
