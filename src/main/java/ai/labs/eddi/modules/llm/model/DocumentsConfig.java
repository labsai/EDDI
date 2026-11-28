/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The {@code documents} block of an LLM task: how the agent treats PDF
 * documents — structure, splitting, extracted media, sharing with other agents,
 * the library, large documents.
 *
 * <p>
 * <b>Every field is nullable and has no initializer.</b> "Unset" must stay
 * distinguishable from "set to the default", so that an operator default
 * ({@code eddi.documents.defaults.<path>}) can apply and an untouched
 * configuration saves byte-identical. Effective values are never read from this
 * class directly: {@code EffectiveDocumentsConfig.resolve} resolves designer
 * value → operator default → built-in default, then clamps to the operator
 * bound.
 * </p>
 *
 * <p>
 * Enum-like fields are {@code String}s here, validated against known names by
 * {@link DocumentsConfigValidator}, so that this class depends on no extraction
 * type. A key whose behaviour has not shipped yet is refused at save ("not
 * available in this version") — see {@link DocumentsConfigValidator#LIVE_KEYS}
 * for what is available.
 * </p>
 *
 * <p>
 * The shape is the whole agent-level {@code documents} JSON of the design (the
 * PDF documents design, §14), so a client that sends a key before it is
 * available gets the "not available" refusal rather than the strict REST
 * boundary's unknown-key 400.
 * </p>
 *
 * @since 6.8.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentsConfig {

    /** {@code OFF} | {@code ON_DEMAND} | {@code AUTO_EXPAND}. */
    private String structure;
    private Split split;
    /** {@code AUTO} | {@code BLOCKS}. */
    private String unitForm;
    private Images images;
    private Tables tables;
    private Audio audio;
    private EmbeddedFiles embeddedFiles;
    private Derived derived;
    private Sharing sharing;
    private Library library;
    private Index index;
    private Large large;
    private Bridge bridge;

    public String getStructure() {
        return structure;
    }

    public void setStructure(String structure) {
        this.structure = structure;
    }

    public Split getSplit() {
        return split;
    }

    public void setSplit(Split split) {
        this.split = split;
    }

    public String getUnitForm() {
        return unitForm;
    }

    public void setUnitForm(String unitForm) {
        this.unitForm = unitForm;
    }

    public Images getImages() {
        return images;
    }

    public void setImages(Images images) {
        this.images = images;
    }

    public Tables getTables() {
        return tables;
    }

    public void setTables(Tables tables) {
        this.tables = tables;
    }

    public Audio getAudio() {
        return audio;
    }

    public void setAudio(Audio audio) {
        this.audio = audio;
    }

    public EmbeddedFiles getEmbeddedFiles() {
        return embeddedFiles;
    }

    public void setEmbeddedFiles(EmbeddedFiles embeddedFiles) {
        this.embeddedFiles = embeddedFiles;
    }

    public Derived getDerived() {
        return derived;
    }

    public void setDerived(Derived derived) {
        this.derived = derived;
    }

    public Sharing getSharing() {
        return sharing;
    }

    public void setSharing(Sharing sharing) {
        this.sharing = sharing;
    }

    public Library getLibrary() {
        return library;
    }

    public void setLibrary(Library library) {
        this.library = library;
    }

    public Index getIndex() {
        return index;
    }

    public void setIndex(Index index) {
        this.index = index;
    }

    public Large getLarge() {
        return large;
    }

    public void setLarge(Large large) {
        this.large = large;
    }

    public Bridge getBridge() {
        return bridge;
    }

    public void setBridge(Bridge bridge) {
        this.bridge = bridge;
    }

    /** How a document is cut into units (chapters). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Split {
        /** {@code AUTO} | {@code OUTLINE} | {@code HEADINGS} | {@code PAGES}. */
        private String strategy;
        private Integer pagesPerUnit;
        private Integer maxUnitTokens;
        private Integer minUnitTokens;
        private Integer maxUnits;

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public Integer getPagesPerUnit() {
            return pagesPerUnit;
        }

        public void setPagesPerUnit(Integer pagesPerUnit) {
            this.pagesPerUnit = pagesPerUnit;
        }

        public Integer getMaxUnitTokens() {
            return maxUnitTokens;
        }

        public void setMaxUnitTokens(Integer maxUnitTokens) {
            this.maxUnitTokens = maxUnitTokens;
        }

        public Integer getMinUnitTokens() {
            return minUnitTokens;
        }

        public void setMinUnitTokens(Integer minUnitTokens) {
            this.minUnitTokens = minUnitTokens;
        }

        public Integer getMaxUnits() {
            return maxUnits;
        }

        public void setMaxUnits(Integer maxUnits) {
            this.maxUnits = maxUnits;
        }
    }

    /** Images extracted from or rendered of a document. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Images {
        /** {@code EMBEDDED}. */
        private String mode;
        /** {@code NEVER} | {@code SCANNED} | {@code VECTOR} | {@code ALWAYS}. */
        private String pageRender;
        private Integer maxPerUnit;
        private Integer maxEdgePx;
        private Integer minEdgePx;
        private Integer renderDpi;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getPageRender() {
            return pageRender;
        }

        public void setPageRender(String pageRender) {
            this.pageRender = pageRender;
        }

        public Integer getMaxPerUnit() {
            return maxPerUnit;
        }

        public void setMaxPerUnit(Integer maxPerUnit) {
            this.maxPerUnit = maxPerUnit;
        }

        public Integer getMaxEdgePx() {
            return maxEdgePx;
        }

        public void setMaxEdgePx(Integer maxEdgePx) {
            this.maxEdgePx = maxEdgePx;
        }

        public Integer getMinEdgePx() {
            return minEdgePx;
        }

        public void setMinEdgePx(Integer minEdgePx) {
            this.minEdgePx = minEdgePx;
        }

        public Integer getRenderDpi() {
            return renderDpi;
        }

        public void setRenderDpi(Integer renderDpi) {
            this.renderDpi = renderDpi;
        }
    }

    /** Tables found in a document. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tables {
        /** {@code NEVER} | {@code LOW_CONFIDENCE} | {@code ALWAYS}. */
        private String image;
        /** {@code MARKDOWN} | {@code CSV} | {@code JSON}. */
        private String format;
        private Integer maxInlineRows;

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public Integer getMaxInlineRows() {
            return maxInlineRows;
        }

        public void setMaxInlineRows(Integer maxInlineRows) {
            this.maxInlineRows = maxInlineRows;
        }
    }

    /** Audio embedded in a document. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Audio {
        private Boolean extract;
        private Integer maxFiles;
        private Integer maxPerUnit;
        private Integer maxDurationSeconds;

        public Boolean getExtract() {
            return extract;
        }

        public void setExtract(Boolean extract) {
            this.extract = extract;
        }

        public Integer getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(Integer maxFiles) {
            this.maxFiles = maxFiles;
        }

        public Integer getMaxPerUnit() {
            return maxPerUnit;
        }

        public void setMaxPerUnit(Integer maxPerUnit) {
            this.maxPerUnit = maxPerUnit;
        }

        public Integer getMaxDurationSeconds() {
            return maxDurationSeconds;
        }

        public void setMaxDurationSeconds(Integer maxDurationSeconds) {
            this.maxDurationSeconds = maxDurationSeconds;
        }
    }

    /** Files embedded in a document. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EmbeddedFiles {
        /** {@code NONE}. */
        private String extract;
        private List<String> mimeTypes;

        public String getExtract() {
            return extract;
        }

        public void setExtract(String extract) {
            this.extract = extract;
        }

        public List<String> getMimeTypes() {
            return mimeTypes;
        }

        public void setMimeTypes(List<String> mimeTypes) {
            this.mimeTypes = mimeTypes;
        }
    }

    /** Caps on stored views derived from one document. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Derived {
        private Integer maxPerSource;
        private Long maxBytesPerSource;

        public Integer getMaxPerSource() {
            return maxPerSource;
        }

        public void setMaxPerSource(Integer maxPerSource) {
            this.maxPerSource = maxPerSource;
        }

        public Long getMaxBytesPerSource() {
            return maxBytesPerSource;
        }

        public void setMaxBytesPerSource(Long maxBytesPerSource) {
            this.maxBytesPerSource = maxBytesPerSource;
        }
    }

    /** Handing files to other agents. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Sharing {
        private Boolean delegation;
        private Boolean handoff;
        private Integer maxFilesPerShare;
        private Integer maxSharesPerTurn;
        private Integer maxDepth;

        public Boolean getDelegation() {
            return delegation;
        }

        public void setDelegation(Boolean delegation) {
            this.delegation = delegation;
        }

        public Boolean getHandoff() {
            return handoff;
        }

        public void setHandoff(Boolean handoff) {
            this.handoff = handoff;
        }

        public Integer getMaxFilesPerShare() {
            return maxFilesPerShare;
        }

        public void setMaxFilesPerShare(Integer maxFilesPerShare) {
            this.maxFilesPerShare = maxFilesPerShare;
        }

        public Integer getMaxSharesPerTurn() {
            return maxSharesPerTurn;
        }

        public void setMaxSharesPerTurn(Integer maxSharesPerTurn) {
            this.maxSharesPerTurn = maxSharesPerTurn;
        }

        public Integer getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(Integer maxDepth) {
            this.maxDepth = maxDepth;
        }
    }

    /** The personal and team document library. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Library {
        private Boolean enabled;
        /** {@code TOOL}. */
        private String promote;
        /** Each of {@code self} | {@code global} | {@code team}. */
        private List<String> scopes;
        private List<String> teams;
        private Boolean memoryCard;
        private Integer maxCardsRecalled;
        /** Absent = the deployment default. */
        private Integer retentionDays;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getPromote() {
            return promote;
        }

        public void setPromote(String promote) {
            this.promote = promote;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes;
        }

        public List<String> getTeams() {
            return teams;
        }

        public void setTeams(List<String> teams) {
            this.teams = teams;
        }

        public Boolean getMemoryCard() {
            return memoryCard;
        }

        public void setMemoryCard(Boolean memoryCard) {
            this.memoryCard = memoryCard;
        }

        public Integer getMaxCardsRecalled() {
            return maxCardsRecalled;
        }

        public void setMaxCardsRecalled(Integer maxCardsRecalled) {
            this.maxCardsRecalled = maxCardsRecalled;
        }

        public Integer getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(Integer retentionDays) {
            this.retentionDays = retentionDays;
        }
    }

    /** The semantic document index. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Index {
        /** A RAG configuration flagged as a document index. */
        private String rag;

        public String getRag() {
            return rag;
        }

        public void setRag(String rag) {
            this.rag = rag;
        }
    }

    /** Documents too large to forward whole. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Large {
        private Integer thresholdPages;
        private Long thresholdBytes;
        private Boolean processOnUpload;
        /** {@code NONE} | {@code NOTE}. */
        private String onReady;
        private Summary summary;

        public Integer getThresholdPages() {
            return thresholdPages;
        }

        public void setThresholdPages(Integer thresholdPages) {
            this.thresholdPages = thresholdPages;
        }

        public Long getThresholdBytes() {
            return thresholdBytes;
        }

        public void setThresholdBytes(Long thresholdBytes) {
            this.thresholdBytes = thresholdBytes;
        }

        public Boolean getProcessOnUpload() {
            return processOnUpload;
        }

        public void setProcessOnUpload(Boolean processOnUpload) {
            this.processOnUpload = processOnUpload;
        }

        public String getOnReady() {
            return onReady;
        }

        public void setOnReady(String onReady) {
            this.onReady = onReady;
        }

        public Summary getSummary() {
            return summary;
        }

        public void setSummary(Summary summary) {
            this.summary = summary;
        }

        /** The background map-reduce summary of a large document. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class Summary {
            /** The LLM configuration that writes the summary. */
            private String llm;
            private Integer leafOutputTokens;
            private Integer reduceFanIn;
            private Integer maxLevels;
            private Integer maxParallelUnits;
            private Double maxCostPerDocument;
            /** {@code CAPTIONS} | {@code KEY} | {@code ALL}. */
            private String images;

            public String getLlm() {
                return llm;
            }

            public void setLlm(String llm) {
                this.llm = llm;
            }

            public Integer getLeafOutputTokens() {
                return leafOutputTokens;
            }

            public void setLeafOutputTokens(Integer leafOutputTokens) {
                this.leafOutputTokens = leafOutputTokens;
            }

            public Integer getReduceFanIn() {
                return reduceFanIn;
            }

            public void setReduceFanIn(Integer reduceFanIn) {
                this.reduceFanIn = reduceFanIn;
            }

            public Integer getMaxLevels() {
                return maxLevels;
            }

            public void setMaxLevels(Integer maxLevels) {
                this.maxLevels = maxLevels;
            }

            public Integer getMaxParallelUnits() {
                return maxParallelUnits;
            }

            public void setMaxParallelUnits(Integer maxParallelUnits) {
                this.maxParallelUnits = maxParallelUnits;
            }

            public Double getMaxCostPerDocument() {
                return maxCostPerDocument;
            }

            public void setMaxCostPerDocument(Double maxCostPerDocument) {
                this.maxCostPerDocument = maxCostPerDocument;
            }

            public String getImages() {
                return images;
            }

            public void setImages(String images) {
                this.images = images;
            }
        }
    }

    /**
     * The modality bridge (audio transcription, image description for models that
     * cannot take the medium). The design names only the block
     * ({@code "bridge": null} = off); its fields arrive with the package that ships
     * it. Until then the block itself is the key: any value is refused at save as
     * not available.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Bridge {
    }
}
