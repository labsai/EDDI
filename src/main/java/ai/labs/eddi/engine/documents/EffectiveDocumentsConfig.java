/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.modules.llm.model.DocumentsConfig;
import ai.labs.eddi.modules.llm.model.DocumentsConfigValidator;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The values an LLM task's {@code documents} block actually takes, after
 * resolution: <b>designer value → operator default
 * ({@code eddi.documents.defaults.<path>}) → built-in default</b>, then clamped
 * to the operator bound ({@link DocumentBounds}).
 *
 * <p>
 * A key that is not available in this version
 * ({@link DocumentsConfigValidator#LIVE_KEYS}) resolves to its built-in ("off")
 * value whatever was stored or configured. A stored value the save-time
 * validator would refuse today — written directly to the database, saved by a
 * newer version, or above a bound tightened later — never throws here: it is
 * ignored or clamped, and the reason is reported in {@link #clampWarnings()}
 * for the caller to log once. Runtime code reads only this record, never
 * {@link DocumentsConfig}.
 * </p>
 *
 * @param clampWarnings
 *            every value ignored or clamped during resolution, one sentence
 *            each, naming the key (never a value from a document)
 */
public record EffectiveDocumentsConfig(Structure structure, Split split, UnitForm unitForm, Images images, Tables tables, Audio audio,
        EmbeddedFiles embeddedFiles, Derived derived, Sharing sharing, Library library, Index index, Large large, Bridge bridge,
        List<String> clampWarnings) {

    public EffectiveDocumentsConfig {
        clampWarnings = List.copyOf(clampWarnings);
    }

    public enum Structure {
        OFF, ON_DEMAND, AUTO_EXPAND
    }

    public enum UnitForm {
        AUTO, BLOCKS
    }

    public enum ImageMode {
        EMBEDDED
    }

    public enum PageRender {
        NEVER, SCANNED, VECTOR, ALWAYS
    }

    public enum TableImage {
        NEVER, LOW_CONFIDENCE, ALWAYS
    }

    public enum TableFormat {
        MARKDOWN, CSV, JSON
    }

    public enum EmbeddedExtract {
        NONE
    }

    public enum Promote {
        TOOL
    }

    public enum OnReady {
        NONE, NOTE
    }

    public enum SummaryImages {
        CAPTIONS, KEY, ALL
    }

    public record Split(Strategy strategy, int pagesPerUnit, int maxUnitTokens, int minUnitTokens, int maxUnits) {
        public enum Strategy {
            AUTO, OUTLINE, HEADINGS, PAGES
        }
    }

    public record Images(ImageMode mode, PageRender pageRender, int maxPerUnit, int maxEdgePx, int minEdgePx, int renderDpi) {
    }

    public record Tables(TableImage image, TableFormat format, int maxInlineRows) {
    }

    public record Audio(boolean extract, int maxFiles, int maxPerUnit, int maxDurationSeconds) {
    }

    public record EmbeddedFiles(EmbeddedExtract extract, List<String> mimeTypes) {
        public EmbeddedFiles {
            mimeTypes = List.copyOf(mimeTypes);
        }
    }

    public record Derived(int maxPerSource, long maxBytesPerSource) {
    }

    public record Sharing(boolean delegation, boolean handoff, int maxFilesPerShare, int maxSharesPerTurn, int maxDepth) {
    }

    /**
     * @param retentionDays
     *            {@code null} = the deployment's retention default
     */
    public record Library(boolean enabled, Promote promote, List<String> scopes, List<String> teams, boolean memoryCard,
            int maxCardsRecalled, Integer retentionDays) {
        public Library {
            scopes = List.copyOf(scopes);
            teams = List.copyOf(teams);
        }
    }

    /**
     * @param rag
     *            {@code null} = no semantic document index
     */
    public record Index(String rag) {
    }

    public record Large(int thresholdPages, long thresholdBytes, boolean processOnUpload, OnReady onReady, Summary summary) {
        /**
         * @param llm
         *            {@code null} = no summary model configured
         */
        public record Summary(String llm, int leafOutputTokens, int reduceFanIn, int maxLevels, int maxParallelUnits,
                double maxCostPerDocument, SummaryImages images) {
        }
    }

    /**
     * The modality bridge; {@code null} in
     * {@link EffectiveDocumentsConfig#bridge()} means off.
     */
    public record Bridge() {
    }

    /**
     * Everything at its built-in default: what an agent that configures nothing
     * gets.
     */
    public static EffectiveDocumentsConfig defaults() {
        return resolve(null, DocumentSettings.none());
    }

    /** Resolution against the keys available in this version. */
    public static EffectiveDocumentsConfig resolve(DocumentsConfig designerOrNull, DocumentSettings settings) {
        return resolve(designerOrNull, settings, DocumentsConfigValidator.LIVE_KEYS);
    }

    /** The whole contract, with the live set supplied. */
    static EffectiveDocumentsConfig resolve(DocumentsConfig d, DocumentSettings settings, Set<String> liveKeys) {
        var r = new Resolver(settings != null ? settings : DocumentSettings.none(), liveKeys);
        DocumentsConfig c = d != null ? d : new DocumentsConfig();

        var sp = c.getSplit() != null ? c.getSplit() : new DocumentsConfig.Split();
        var im = c.getImages() != null ? c.getImages() : new DocumentsConfig.Images();
        var tb = c.getTables() != null ? c.getTables() : new DocumentsConfig.Tables();
        var au = c.getAudio() != null ? c.getAudio() : new DocumentsConfig.Audio();
        var ef = c.getEmbeddedFiles() != null ? c.getEmbeddedFiles() : new DocumentsConfig.EmbeddedFiles();
        var dv = c.getDerived() != null ? c.getDerived() : new DocumentsConfig.Derived();
        var sh = c.getSharing() != null ? c.getSharing() : new DocumentsConfig.Sharing();
        var lb = c.getLibrary() != null ? c.getLibrary() : new DocumentsConfig.Library();
        var ix = c.getIndex() != null ? c.getIndex() : new DocumentsConfig.Index();
        var lg = c.getLarge() != null ? c.getLarge() : new DocumentsConfig.Large();
        var sm = lg.getSummary() != null ? lg.getSummary() : new DocumentsConfig.Large.Summary();

        var split = new Split(r.enumValue("split.strategy", sp.getStrategy(), Split.Strategy.class, Split.Strategy.AUTO),
                r.intValue("split.pagesPerUnit", sp.getPagesPerUnit(), 10),
                r.intValue("split.maxUnitTokens", sp.getMaxUnitTokens(), 12_000),
                r.intValue("split.minUnitTokens", sp.getMinUnitTokens(), 500),
                r.intValue("split.maxUnits", sp.getMaxUnits(), 60));
        var images = new Images(r.enumValue("images.mode", im.getMode(), ImageMode.class, ImageMode.EMBEDDED),
                r.enumValue("images.pageRender", im.getPageRender(), PageRender.class, PageRender.NEVER),
                r.intValue("images.maxPerUnit", im.getMaxPerUnit(), 20),
                r.intValue("images.maxEdgePx", im.getMaxEdgePx(), 1568),
                r.intValue("images.minEdgePx", im.getMinEdgePx(), 48),
                r.intValue("images.renderDpi", im.getRenderDpi(), 110));
        var tables = new Tables(r.enumValue("tables.image", tb.getImage(), TableImage.class, TableImage.NEVER),
                r.enumValue("tables.format", tb.getFormat(), TableFormat.class, TableFormat.MARKDOWN),
                r.intValue("tables.maxInlineRows", tb.getMaxInlineRows(), 60));
        var audio = new Audio(r.boolValue("audio.extract", au.getExtract(), false),
                r.intValue("audio.maxFiles", au.getMaxFiles(), 10),
                r.intValue("audio.maxPerUnit", au.getMaxPerUnit(), 2),
                r.intValue("audio.maxDurationSeconds", au.getMaxDurationSeconds(), 600));
        var embedded = new EmbeddedFiles(r.enumValue("embeddedFiles.extract", ef.getExtract(), EmbeddedExtract.class, EmbeddedExtract.NONE),
                r.listValue("embeddedFiles.mimeTypes", ef.getMimeTypes(), List.of()));
        var derived = new Derived(r.intValue("derived.maxPerSource", dv.getMaxPerSource(), 200),
                r.longValue("derived.maxBytesPerSource", dv.getMaxBytesPerSource(), 52_428_800L));
        var sharing = new Sharing(r.boolValue("sharing.delegation", sh.getDelegation(), false),
                r.boolValue("sharing.handoff", sh.getHandoff(), false),
                r.intValue("sharing.maxFilesPerShare", sh.getMaxFilesPerShare(), 5),
                r.intValue("sharing.maxSharesPerTurn", sh.getMaxSharesPerTurn(), 10),
                r.intValue("sharing.maxDepth", sh.getMaxDepth(), 3));
        var library = new Library(r.boolValue("library.enabled", lb.getEnabled(), false),
                r.enumValue("library.promote", lb.getPromote(), Promote.class, Promote.TOOL),
                r.listValue("library.scopes", lb.getScopes(), List.of("self")),
                r.listValue("library.teams", lb.getTeams(), List.of()),
                r.boolValue("library.memoryCard", lb.getMemoryCard(), true),
                r.intValue("library.maxCardsRecalled", lb.getMaxCardsRecalled(), 5),
                r.nullableIntValue("library.retentionDays", lb.getRetentionDays()));
        var index = new Index(r.nullableString("index.rag", ix.getRag()));
        var summary = new Large.Summary(r.nullableString("large.summary.llm", sm.getLlm()),
                r.intValue("large.summary.leafOutputTokens", sm.getLeafOutputTokens(), 300),
                r.intValue("large.summary.reduceFanIn", sm.getReduceFanIn(), 12),
                r.intValue("large.summary.maxLevels", sm.getMaxLevels(), 4),
                r.intValue("large.summary.maxParallelUnits", sm.getMaxParallelUnits(), 4),
                r.doubleValue("large.summary.maxCostPerDocument", sm.getMaxCostPerDocument(), 5.00),
                r.enumValue("large.summary.images", sm.getImages(), SummaryImages.class, SummaryImages.CAPTIONS));
        var large = new Large(r.intValue("large.thresholdPages", lg.getThresholdPages(), 80),
                r.longValue("large.thresholdBytes", lg.getThresholdBytes(), 10_485_760L),
                r.boolValue("large.processOnUpload", lg.getProcessOnUpload(), false),
                r.enumValue("large.onReady", lg.getOnReady(), OnReady.class, OnReady.NOTE), summary);
        Bridge bridge = r.bridge(c.getBridge());

        return new EffectiveDocumentsConfig(r.enumValue("structure", c.getStructure(), Structure.class, Structure.OFF), split,
                r.enumValue("unitForm", c.getUnitForm(), UnitForm.class, UnitForm.AUTO), images, tables, audio, embedded, derived,
                sharing, library, index, large, bridge, r.warnings);
    }

    /**
     * Resolves one key at a time. For each: a designer value applies if its key is
     * live; otherwise an operator default applies if <em>its</em> value is live;
     * otherwise the built-in default. A value that cannot be parsed is ignored with
     * a warning; a number is clamped to its bound with a warning.
     */
    private static final class Resolver {
        private final DocumentSettings settings;
        private final Set<String> liveKeys;
        private final List<String> warnings = new ArrayList<>();

        private final DocumentBounds bounds;

        Resolver(DocumentSettings settings, Set<String> liveKeys) {
            this.settings = settings;
            this.liveKeys = liveKeys;
            DocumentBounds read;
            try {
                read = settings.bounds();
            } catch (RuntimeException e) {
                warnings.add("the operator bounds cannot be read and are ignored");
                read = DocumentBounds.none();
            }
            this.bounds = read != null ? read : DocumentBounds.none();
        }

        private boolean live(String path, String textValue) {
            return DocumentsConfigValidator.isLive(path, textValue == null ? null : TextNode.valueOf(textValue), liveKeys);
        }

        private boolean liveList(String path, List<String> values) {
            var array = JsonNodeFactory.instance.arrayNode();
            values.forEach(array::add);
            return DocumentsConfigValidator.isLive(path, array, liveKeys);
        }

        private void ignoredDesigner(String path) {
            warnings.add("documents." + path + " is not available in this version and is ignored");
        }

        private void ignoredDefault(String path, String why) {
            warnings.add(DocumentSettings.DEFAULTS_PREFIX + path + " " + why + " and is ignored");
        }

        /**
         * The designer value if live, else the operator default parsed by {@code parse}
         * if live, else null.
         */
        private <T> T pick(String path, T designer, String designerText, Function<String, T> parse) {
            if (designer != null) {
                if (live(path, designerText)) {
                    return designer;
                }
                ignoredDesigner(path);
            }
            var configured = readDefault(path);
            if (configured.isEmpty()) {
                return null;
            }
            if (!live(path, configured.get())) {
                ignoredDefault(path, "configures a key that is not available in this version");
                return null;
            }
            try {
                T parsed = parse.apply(configured.get());
                if (parsed == null) {
                    ignoredDefault(path, "is not a valid value");
                }
                return parsed;
            } catch (RuntimeException e) {
                ignoredDefault(path, "is not a valid value");
                return null;
            }
        }

        int intValue(String path, Integer designer, int builtIn) {
            Integer v = pick(path, designer, null, Resolver::parseInteger);
            return (int) clamp(path, v != null ? v : builtIn, builtIn);
        }

        Integer nullableIntValue(String path, Integer designer) {
            Integer v = pick(path, designer, null, Resolver::parseInteger);
            if (v == null) {
                return null;
            }
            if (v < 0) {
                warnings.add("documents." + path + " must not be negative; the default applies");
                return null;
            }
            return (int) clamp(path, v, 0);
        }

        long longValue(String path, Long designer, long builtIn) {
            Long v = pick(path, designer, null, Resolver::parseLong);
            return (long) clamp(path, v != null ? v : builtIn, builtIn);
        }

        double doubleValue(String path, Double designer, double builtIn) {
            Double v = pick(path, designer, null, Resolver::parseDouble);
            return clamp(path, v != null ? v : builtIn, builtIn);
        }

        boolean boolValue(String path, Boolean designer, boolean builtIn) {
            Boolean v = pick(path, designer, null, s -> switch (s.toLowerCase(Locale.ROOT)) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> null;
            });
            return v != null ? v : builtIn;
        }

        String nullableString(String path, String designer) {
            String v = pick(path, designer == null || designer.isBlank() ? null : designer, null, s -> s);
            return v == null || v.isBlank() ? null : v.strip();
        }

        <E extends Enum<E>> E enumValue(String path, String designer, Class<E> type, E builtIn) {
            E fromDesigner = null;
            if (designer != null) {
                if (!live(path, designer)) {
                    ignoredDesigner(path);
                } else {
                    fromDesigner = parseEnum(type, designer);
                    if (fromDesigner == null) {
                        warnings.add("documents." + path + " has an unknown value and is ignored");
                    }
                }
            }
            if (fromDesigner != null) {
                return fromDesigner;
            }
            E fromDefault = pick(path, null, null, s -> parseEnum(type, s));
            return fromDefault != null ? fromDefault : builtIn;
        }

        List<String> listValue(String path, List<String> designer, List<String> builtIn) {
            if (designer != null) {
                if (liveList(path, designer)) {
                    return knownOnly(path, designer);
                }
                ignoredDesigner(path);
            }
            var configured = readDefault(path);
            if (configured.isEmpty()) {
                return builtIn;
            }
            List<String> parsed = Arrays.stream(configured.get().split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (!liveList(path, parsed)) {
                ignoredDefault(path, "configures a key that is not available in this version");
                return builtIn;
            }
            return knownOnly(path, parsed);
        }

        /**
         * Drops list elements that are not known names, for a list with a closed set of
         * names.
         */
        private List<String> knownOnly(String path, List<String> values) {
            Set<String> known = DocumentsConfigValidator.knownValues(path);
            if (known.isEmpty()) {
                return values.stream().filter(v -> v != null && !v.isBlank()).toList();
            }
            // Set.of(...).contains(null) throws, and a stored list may hold a null
            List<String> kept = values.stream().filter(v -> v != null && known.contains(v)).toList();
            if (kept.size() != values.size()) {
                warnings.add("documents." + path + " contains unknown values, which are ignored");
            }
            return kept;
        }

        Bridge bridge(DocumentsConfig.Bridge designer) {
            if (designer == null) {
                return null;
            }
            if (liveKeys.contains("bridge")) {
                return new Bridge();
            }
            ignoredDesigner("bridge");
            return null;
        }

        private double clamp(String path, double value, double fallback) {
            if (!Double.isFinite(value) || value < 0) {
                warnings.add("documents." + path + " must not be negative; the default applies");
                return fallback;
            }
            var bound = bounds.boundFor(path);
            if (bound.isPresent() && value > bound.get()) {
                warnings.add("documents." + path + " is above the operator bound of " + formatNumber(bound.get()) + " ("
                        + DocumentBounds.propertyFor(path) + ") and is clamped to it");
                return bound.get();
            }
            return value;
        }

        /**
         * The operator default for {@code path}; a lookup that fails counts as unset,
         * with a warning.
         */
        private Optional<String> readDefault(String path) {
            try {
                return settings.defaultFor(path);
            } catch (RuntimeException e) {
                ignoredDefault(path, "cannot be read");
                return Optional.empty();
            }
        }

        /** An operator-default number, or null when the text is not one. */
        private static Integer parseInteger(String text) {
            try {
                return Integer.valueOf(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static Long parseLong(String text) {
            try {
                return Long.valueOf(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static Double parseDouble(String text) {
            try {
                return Double.valueOf(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static <E extends Enum<E>> E parseEnum(Class<E> type, String name) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(name.strip())) {
                    return constant;
                }
            }
            return null;
        }

        private static String formatNumber(double value) {
            return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
        }
    }
}
