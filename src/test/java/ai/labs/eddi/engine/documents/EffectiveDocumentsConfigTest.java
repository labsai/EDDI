/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.EmbeddedExtract;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.ImageMode;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.OnReady;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.PageRender;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.Promote;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.Split;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.Structure;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.SummaryImages;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.TableFormat;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.TableImage;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig.UnitForm;
import ai.labs.eddi.modules.llm.model.DocumentsConfig;
import ai.labs.eddi.modules.llm.model.DocumentsConfigValidator;
import ai.labs.eddi.modules.llm.model.DocumentsDesignJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EffectiveDocumentsConfig")
class EffectiveDocumentsConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DocumentsConfig parse(String json) throws Exception {
        return MAPPER.readValue(json, DocumentsConfig.class);
    }

    /**
     * Every default of the design's agent JSON (§14), as the plan's §3.5 lists them
     * — written out, not read from the code under test.
     */
    private static void assertDesignDefaults(EffectiveDocumentsConfig e) {
        assertAll(() -> assertEquals(Structure.OFF, e.structure()),
                () -> assertEquals(new Split(Split.Strategy.AUTO, 10, 12000, 500, 60), e.split()),
                () -> assertEquals(UnitForm.AUTO, e.unitForm()),
                () -> assertEquals(new EffectiveDocumentsConfig.Images(ImageMode.EMBEDDED, PageRender.NEVER, 20, 1568, 48, 110), e.images()),
                () -> assertEquals(new EffectiveDocumentsConfig.Tables(TableImage.NEVER, TableFormat.MARKDOWN, 60), e.tables()),
                () -> assertEquals(new EffectiveDocumentsConfig.Audio(false, 10, 2, 600), e.audio()),
                () -> assertEquals(new EffectiveDocumentsConfig.EmbeddedFiles(EmbeddedExtract.NONE, List.of()), e.embeddedFiles()),
                () -> assertEquals(new EffectiveDocumentsConfig.Derived(200, 52_428_800L), e.derived()),
                () -> assertEquals(new EffectiveDocumentsConfig.Sharing(false, false, 5, 10, 3), e.sharing()),
                () -> assertEquals(new EffectiveDocumentsConfig.Library(false, Promote.TOOL, List.of("self"), List.of(), true, 5, null),
                        e.library()),
                () -> assertNull(e.index().rag()),
                () -> assertEquals(80, e.large().thresholdPages()), () -> assertEquals(10_485_760L, e.large().thresholdBytes()),
                () -> assertFalse(e.large().processOnUpload()), () -> assertEquals(OnReady.NOTE, e.large().onReady()),
                () -> assertEquals(new EffectiveDocumentsConfig.Large.Summary(null, 300, 12, 4, 4, 5.00, SummaryImages.CAPTIONS),
                        e.large().summary()),
                () -> assertNull(e.bridge()));
    }

    private static Set<String> allKeysLive() {
        Set<String> live = new HashSet<>(DocumentsDesignJson.LEAF_KEYS);
        DocumentsDesignJson.NULL_IN_DESIGN.forEach(k -> live.add(k.get(0)));
        live.add("bridge");
        return live;
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("an absent block resolves to every design default, without warnings")
        void absentBlock() {
            var e = EffectiveDocumentsConfig.resolve(null, DocumentSettings.none());
            assertDesignDefaults(e);
            assertTrue(e.clampWarnings().isEmpty(), e.clampWarnings().toString());
            assertEquals(e, EffectiveDocumentsConfig.defaults());
        }

        @Test
        @DisplayName("the design JSON itself resolves to the same values when every key is live")
        void designJsonWhenLive() throws Exception {
            var e = EffectiveDocumentsConfig.resolve(parse(DocumentsDesignJson.FULL), DocumentSettings.none(), allKeysLive());
            assertDesignDefaults(e);
            assertTrue(e.clampWarnings().isEmpty(), e.clampWarnings().toString());
        }

        @Test
        @DisplayName("an empty block and empty nested blocks resolve to the defaults")
        void emptyBlocks() throws Exception {
            assertDesignDefaults(EffectiveDocumentsConfig.resolve(parse("{}"), DocumentSettings.none()));
            assertDesignDefaults(
                    EffectiveDocumentsConfig.resolve(parse("{\"split\":{},\"large\":{\"summary\":{}}}"), DocumentSettings.none()));
        }

        @Test
        @DisplayName("the validator's known names are exactly the Java enums' constants")
        void enumNamesAgree() {
            Map<String, Class<? extends Enum<?>>> enums = Map.ofEntries(Map.entry("structure", Structure.class),
                    Map.entry("split.strategy", Split.Strategy.class), Map.entry("unitForm", UnitForm.class),
                    Map.entry("images.mode", ImageMode.class), Map.entry("images.pageRender", PageRender.class),
                    Map.entry("tables.image", TableImage.class), Map.entry("tables.format", TableFormat.class),
                    Map.entry("embeddedFiles.extract", EmbeddedExtract.class), Map.entry("library.promote", Promote.class),
                    Map.entry("large.onReady", OnReady.class), Map.entry("large.summary.images", SummaryImages.class));
            enums.forEach((path, type) -> assertEquals(
                    Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toSet()),
                    DocumentsConfigValidator.knownValues(path), path));
        }
    }

    @Nested
    @DisplayName("not available in this version")
    class NotLive {

        @Test
        @DisplayName("a stored key that is not live resolves to off, with one warning naming it")
        void storedNonLiveKeyIsIgnored() throws Exception {
            var e = EffectiveDocumentsConfig.resolve(parse("{\"sharing\":{\"delegation\":true}}"), DocumentSettings.none());
            assertFalse(e.sharing().delegation());
            assertEquals(List.of("documents.sharing.delegation is not available in this version and is ignored"), e.clampWarnings());
        }

        @Test
        @DisplayName("every design key set to a non-default value is ignored while not live")
        void everythingIgnored() throws Exception {
            var e = EffectiveDocumentsConfig.resolve(parse("""
                    {"structure":"AUTO_EXPAND","split":{"maxUnitTokens":1,"strategy":"PAGES"},"library":{"enabled":true,"scopes":["team"]},
                     "index":{"rag":"x"},"large":{"summary":{"maxCostPerDocument":99.0,"llm":"y"}},"bridge":{}}
                    """), DocumentSettings.none());
            assertDesignDefaults(e);
            assertEquals(9, e.clampWarnings().size(), e.clampWarnings().toString());
        }

        @Test
        @DisplayName("an operator default for a key that is not live is ignored too")
        void operatorDefaultForNonLiveKey() {
            var settings = DocumentSettings.of(Map.of("sharing.delegation", "true"), DocumentBounds.none());
            var e = EffectiveDocumentsConfig.resolve(null, settings);
            assertFalse(e.sharing().delegation());
            assertEquals(List.of("eddi.documents.defaults.sharing.delegation configures a key that is not available in this version"
                    + " and is ignored"), e.clampWarnings());
        }

        @Test
        @DisplayName("a value-level live entry admits only that value")
        void valueLevel() throws Exception {
            Set<String> live = Set.of("structure=ON_DEMAND");
            assertEquals(Structure.ON_DEMAND,
                    EffectiveDocumentsConfig.resolve(parse("{\"structure\":\"ON_DEMAND\"}"), DocumentSettings.none(), live).structure());
            var e = EffectiveDocumentsConfig.resolve(parse("{\"structure\":\"AUTO_EXPAND\"}"), DocumentSettings.none(), live);
            assertEquals(Structure.OFF, e.structure());
            assertEquals(1, e.clampWarnings().size());
        }
    }

    @Nested
    @DisplayName("designer → operator default → built-in, then the bound")
    class Precedence {

        private static final Set<String> LIVE = Set.of("split.maxUnitTokens", "large.summary.maxCostPerDocument", "large.onReady",
                "library.scopes", "library.retentionDays");

        @Test
        @DisplayName("an operator default wins over the built-in default")
        void operatorDefaultWins() {
            var settings = DocumentSettings.of(Map.of("split.maxUnitTokens", "8000"), DocumentBounds.none());
            var e = EffectiveDocumentsConfig.resolve(null, settings, LIVE);
            assertEquals(8000, e.split().maxUnitTokens());
            assertTrue(e.clampWarnings().isEmpty());
        }

        @Test
        @DisplayName("a designer value wins over the operator default")
        void designerWins() throws Exception {
            var settings = DocumentSettings.of(Map.of("split.maxUnitTokens", "8000"), DocumentBounds.none());
            var e = EffectiveDocumentsConfig.resolve(parse("{\"split\":{\"maxUnitTokens\":4000}}"), settings, LIVE);
            assertEquals(4000, e.split().maxUnitTokens());
        }

        @Test
        @DisplayName("a bound clamps the operator default and the designer value, with a warning each")
        void boundClampsBoth() throws Exception {
            var bounds = DocumentBounds.of(Map.of("split.maxUnitTokens", 6000));
            var fromDefault = EffectiveDocumentsConfig.resolve(null, DocumentSettings.of(Map.of("split.maxUnitTokens", "8000"), bounds), LIVE);
            assertEquals(6000, fromDefault.split().maxUnitTokens());
            assertEquals(1, fromDefault.clampWarnings().size());
            assertTrue(fromDefault.clampWarnings().get(0).contains("above the operator bound of 6000"), fromDefault.clampWarnings().toString());

            var fromDesigner = EffectiveDocumentsConfig.resolve(parse("{\"split\":{\"maxUnitTokens\":9000}}"),
                    DocumentSettings.of(Map.of(), bounds), LIVE);
            assertEquals(6000, fromDesigner.split().maxUnitTokens());
            assertEquals(1, fromDesigner.clampWarnings().size());
        }

        @Test
        @DisplayName("a bound also clamps the built-in default (an operator who sets the bound below it)")
        void boundClampsBuiltIn() {
            var e = EffectiveDocumentsConfig.resolve(null, DocumentSettings.of(Map.of(), DocumentBounds.of(Map.of("split.maxUnitTokens", 6000))),
                    LIVE);
            assertEquals(6000, e.split().maxUnitTokens());
        }

        @Test
        @DisplayName("a dollar value is clamped as a dollar value")
        void dollarClamp() throws Exception {
            var e = EffectiveDocumentsConfig.resolve(parse("{\"large\":{\"summary\":{\"maxCostPerDocument\":12.5}}}"),
                    DocumentSettings.of(Map.of(), DocumentBounds.of(Map.of("large.summary.maxCostPerDocument", 5.0))), LIVE);
            assertEquals(5.0, e.large().summary().maxCostPerDocument());
        }

        @Test
        @DisplayName("an operator list default is comma-separated")
        void listDefault() {
            var e = EffectiveDocumentsConfig.resolve(null, DocumentSettings.of(Map.of("library.scopes", "self, global"), DocumentBounds.none()),
                    LIVE);
            assertEquals(List.of("self", "global"), e.library().scopes());
        }
    }

    @Nested
    @DisplayName("never throws")
    class NeverThrows {

        private static final Set<String> LIVE = Set.of("split.maxUnitTokens", "large.onReady", "library.scopes", "library.retentionDays",
                "audio.extract");

        @Test
        @DisplayName("an unknown stored enum name resolves to the built-in, with a warning")
        void unknownEnum() throws Exception {
            var e = assertDoesNotThrow(
                    () -> EffectiveDocumentsConfig.resolve(parse("{\"large\":{\"onReady\":\"CONTINUE\"}}"), DocumentSettings.none(), LIVE));
            assertEquals(OnReady.NOTE, e.large().onReady());
            assertEquals(List.of("documents.large.onReady has an unknown value and is ignored"), e.clampWarnings());
        }

        @Test
        @DisplayName("unknown list elements are dropped, with a warning")
        void unknownListElements() throws Exception {
            var e = EffectiveDocumentsConfig.resolve(parse("{\"library\":{\"scopes\":[\"self\",\"world\"]}}"), DocumentSettings.none(), LIVE);
            assertEquals(List.of("self"), e.library().scopes());
            assertEquals(1, e.clampWarnings().size());
        }

        @Test
        @DisplayName("negative stored numbers fall back to the default, with a warning")
        void negativeNumbers() throws Exception {
            var e = EffectiveDocumentsConfig.resolve(parse("{\"split\":{\"maxUnitTokens\":-5},\"library\":{\"retentionDays\":-1}}"),
                    DocumentSettings.none(), LIVE);
            assertEquals(12000, e.split().maxUnitTokens());
            assertNull(e.library().retentionDays());
            assertEquals(2, e.clampWarnings().size(), e.clampWarnings().toString());
        }

        @Test
        @DisplayName("unparsable operator defaults are ignored, with a warning each")
        void badOperatorDefaults() {
            var settings = DocumentSettings.of(Map.of("split.maxUnitTokens", "lots", "large.onReady", "LATER", "audio.extract", "yes"),
                    DocumentBounds.none());
            var e = assertDoesNotThrow(() -> EffectiveDocumentsConfig.resolve(null, settings, LIVE));
            assertEquals(12000, e.split().maxUnitTokens());
            assertEquals(OnReady.NOTE, e.large().onReady());
            assertFalse(e.audio().extract());
            assertEquals(3, e.clampWarnings().size(), e.clampWarnings().toString());
        }
    }
}
