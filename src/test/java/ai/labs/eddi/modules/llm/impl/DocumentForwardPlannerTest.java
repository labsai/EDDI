/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig;
import ai.labs.eddi.modules.llm.impl.DocumentForwardPlanner.Capabilities;
import ai.labs.eddi.modules.llm.impl.DocumentForwardPlanner.Form;
import ai.labs.eddi.modules.llm.impl.DocumentForwardPlanner.Sizes;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentForwardPlanner}: a whole file takes exactly today's path for
 * every capability and configuration; a part is the part, as a PDF or as text,
 * and never the whole file.
 */
class DocumentForwardPlannerTest {

    private static final String REF = "65a1b2c3d4e5f60718293a4b";
    private static final long MB = 1024L * 1024;

    private static final AttachmentSelector WHOLE = AttachmentSelector.wholeFile(REF);
    private static final AttachmentSelector PAGES = new AttachmentSelector(REF, "3-5", null, null);
    private static final AttachmentSelector UNIT = new AttachmentSelector(REF, "12-19", "u3", "abc123");

    /** Every documents configuration the planner can be handed today. */
    private static List<EffectiveDocumentsConfig> configs() {
        List<EffectiveDocumentsConfig> configs = new ArrayList<>();
        configs.add(null);
        configs.add(EffectiveDocumentsConfig.defaults());
        return configs;
    }

    private static Stream<Arguments> wholeFileCases() {
        List<Arguments> cases = new ArrayList<>();
        for (AttachmentSelector selector : Arrays.asList(null, WHOLE)) {
            for (boolean documents : new boolean[]{true, false}) {
                for (EffectiveDocumentsConfig config : configs()) {
                    for (Sizes sizes : Arrays.asList(null, Sizes.beforeSlice(10 * MB, 20 * MB),
                            Sizes.beforeSlice(10 * MB, 0).withSlice(50 * MB))) {
                        cases.add(Arguments.of(selector, documents, config, sizes));
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("wholeFileCases")
    void aWholeFileTakesTodaysPath(AttachmentSelector selector, boolean documents, EffectiveDocumentsConfig config,
                                   Sizes sizes) {
        Form form = DocumentForwardPlanner.plan(selector, new Capabilities(documents), sizes, config);

        assertEquals(documents ? Form.WHOLE_NATIVE : Form.FLAT_TEXT, form);
    }

    @Test
    void noCapabilitiesAreNoDocumentSupport() {
        assertEquals(Form.FLAT_TEXT, DocumentForwardPlanner.plan(WHOLE, null, null, null));
        assertEquals(Form.RANGE_TEXT, DocumentForwardPlanner.plan(PAGES, null, null, null));
    }

    @Nested
    class Parts {

        @Test
        void aDocumentModelGetsASliceWithoutAnyDocumentsBlock() {
            // The acceptance case: attachment_0 {storageRef, pages} with no documents
            // config.
            assertEquals(Form.SLICE_NATIVE,
                    DocumentForwardPlanner.plan(PAGES, new Capabilities(true), Sizes.beforeSlice(10 * MB, 20 * MB), null));
            assertEquals(Form.SLICE_NATIVE,
                    DocumentForwardPlanner.plan(UNIT, new Capabilities(true), null, EffectiveDocumentsConfig.defaults()));
        }

        @Test
        void aTextModelGetsTheRangesText() {
            for (EffectiveDocumentsConfig config : configs()) {
                assertEquals(Form.RANGE_TEXT,
                        DocumentForwardPlanner.plan(PAGES, new Capabilities(false), Sizes.beforeSlice(10 * MB, 20 * MB), config));
                assertEquals(Form.RANGE_TEXT, DocumentForwardPlanner.plan(UNIT, new Capabilities(false), null, config));
            }
        }

        @Test
        void aSliceOverThePerFileLimitBecomesText() {
            Sizes sizes = Sizes.beforeSlice(10 * MB, 20 * MB).withSlice(10 * MB + 1);

            assertEquals(Form.RANGE_TEXT, DocumentForwardPlanner.plan(PAGES, new Capabilities(true), sizes, null));
        }

        @Test
        void aSliceOverWhatIsLeftOfTheRequestBecomesText() {
            Sizes sizes = Sizes.beforeSlice(10 * MB, 2 * MB).withSlice(3 * MB);

            assertEquals(Form.RANGE_TEXT, DocumentForwardPlanner.plan(PAGES, new Capabilities(true), sizes, null));
        }

        @Test
        void aSliceAtTheLimitIsSent() {
            Sizes sizes = Sizes.beforeSlice(10 * MB, 20 * MB).withSlice(10 * MB);

            assertEquals(Form.SLICE_NATIVE, DocumentForwardPlanner.plan(PAGES, new Capabilities(true), sizes, null));
        }

        @Test
        void aPartIsNeverTheWholeFile() {
            for (AttachmentSelector part : List.of(PAGES, UNIT, new AttachmentSelector(REF, null, "u2", null))) {
                for (boolean documents : new boolean[]{true, false}) {
                    for (Sizes sizes : Arrays.asList(null, Sizes.beforeSlice(1, 1).withSlice(100 * MB))) {
                        Form form = DocumentForwardPlanner.plan(part, new Capabilities(documents), sizes, null);
                        assertTrue(form == Form.SLICE_NATIVE || form == Form.RANGE_TEXT, part + " -> " + form);
                    }
                }
            }
        }
    }

    @Nested
    class SliceLimit {

        @Test
        void isThePerFileLimitOrWhatIsLeftWhicheverIsLess() {
            assertEquals(10 * MB, Sizes.beforeSlice(10 * MB, 20 * MB).sliceLimit());
            assertEquals(3 * MB, Sizes.beforeSlice(10 * MB, 3 * MB).sliceLimit());
            assertEquals(0, Sizes.beforeSlice(10 * MB, -5).sliceLimit());
        }
    }

    @Test
    void isPart() {
        assertFalse(DocumentForwardPlanner.isPart(null));
        assertFalse(DocumentForwardPlanner.isPart(WHOLE));
        assertTrue(DocumentForwardPlanner.isPart(PAGES));
        assertTrue(DocumentForwardPlanner.isPart(UNIT));
    }
}
