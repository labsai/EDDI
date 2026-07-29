/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.KnowledgeBaseReference;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.RagDefaults;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers RAG configurations from the workflow, performs retrieval, and
 * formats context for injection into LLM system messages.
 *
 * <p>
 * Follows the same WorkflowTraversal pattern as httpcall and mcpcalls discovery
 * in {@link AgentOrchestrator}.
 * </p>
 */
@ApplicationScoped
public class RagContextProvider {

    private static final Logger LOGGER = Logger.getLogger(RagContextProvider.class);
    private static final String RAG_TYPE = "eddi://ai.labs.rag";

    private final IRestAgentStore restAgentStore;
    private final IRestWorkflowStore restWorkflowStore;
    private final IResourceClientLibrary resourceClientLibrary;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final IDataFactory dataFactory;

    @Inject
    public RagContextProvider(IRestAgentStore restAgentStore, IRestWorkflowStore restWorkflowStore, IResourceClientLibrary resourceClientLibrary,
            EmbeddingModelFactory embeddingModelFactory, EmbeddingStoreFactory embeddingStoreFactory, IDataFactory dataFactory) {
        this.restAgentStore = restAgentStore;
        this.restWorkflowStore = restWorkflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.dataFactory = dataFactory;
    }

    /**
     * Result of a single KB retrieval — the KB name and the content chunk.
     */
    record RetrievalResult(String kbName, Content content) {
    }

    /**
     * Discovers RAG configurations from the workflow and performs retrieval.
     * Returns formatted context string for injection into LLM messages, or null if
     * no RAG is configured/active for this task.
     *
     * @param memory
     *            conversation memory (provides agentId/version)
     * @param task
     *            the LLM task configuration
     * @param userQuery
     *            the user's current input
     * @return formatted context string, or null if no RAG retrieval
     */
    public String retrieveContext(IConversationMemory memory, LlmConfiguration.Task task, String userQuery) {

        // Step 1: Determine which KBs to use
        List<KnowledgeBaseReference> kbRefs = task.getKnowledgeBases();
        boolean hasExplicitRefs = kbRefs != null && !kbRefs.isEmpty();
        boolean useWorkflowDiscovery = !hasExplicitRefs && Boolean.TRUE.equals(task.getEnableWorkflowRag());

        if (!hasExplicitRefs && !useWorkflowDiscovery) {
            return null; // No RAG for this task
        }

        // Step 2: Discover all RAG configs from workflow
        var ragSteps = WorkflowTraversal.discoverConfigs(memory, RAG_TYPE, RagConfiguration.class, restAgentStore, restWorkflowStore,
                resourceClientLibrary);

        if (ragSteps.isEmpty()) {
            LOGGER.debug("No RAG steps found in workflow");
            return null;
        }

        // Step 3: Match KBs by name (or use all if auto-discovery)
        List<RetrievalResult> allResults = new ArrayList<>();
        List<Map<String, Object>> traceEntries = new ArrayList<>();
        var currentStep = memory.getCurrentStep();
        String taskId = task.getId() != null ? task.getId() : "default";

        for (var step : ragSteps) {
            RagConfiguration ragConfig = step.config();
            String kbName = ragConfig.getName();

            // Determine retrieval params
            int maxResults;
            double minScore;

            if (useWorkflowDiscovery) {
                // Use ragDefaults or KB defaults
                RagDefaults defaults = task.getRagDefaults();
                maxResults = defaults != null && defaults.getMaxResults() != null ? defaults.getMaxResults() : ragConfig.getMaxResults();
                minScore = defaults != null && defaults.getMinScore() != null ? defaults.getMinScore() : ragConfig.getMinScore();
            } else {
                // Find matching reference (logically non-null here, but guard for null
                // analysis)
                if (kbRefs == null)
                    continue;
                var ref = kbRefs.stream().filter(r -> kbName.equals(r.getName())).findFirst().orElse(null);
                if (ref == null)
                    continue; // This KB not referenced by task

                maxResults = ref.getMaxResults() != null ? ref.getMaxResults() : ragConfig.getMaxResults();
                minScore = ref.getMinScore() != null ? ref.getMinScore() : ragConfig.getMinScore();
            }

            // Finding I3: chunkStrategy was accepted and never read — ingestion always
            // splits recursively. Surface an unimplemented value as a warning plus a
            // trace entry, but never drop the knowledge base: chunkStrategy is an
            // ingestion-time setting, the documents are already embedded, and they
            // stay retrievable. Rejection belongs at the create/update boundary
            // (RestRagStore), not on the retrieval hot path — failing here would mean
            // a knowledge base that answered fine yesterday silently contributes
            // nothing today.
            String unsupportedSettings = ragConfig.findUnsupportedSettings();
            if (unsupportedSettings != null) {
                LOGGER.warnf("Knowledge base '%s': %s Retrieval continues unaffected.", kbName, unsupportedSettings);
                Map<String, Object> warningTrace = new HashMap<>();
                warningTrace.put("kb", kbName);
                warningTrace.put("warning", unsupportedSettings);
                traceEntries.add(warningTrace);
            }

            try {
                // Step 4: Build EmbeddingModel + EmbeddingStore + ContentRetriever
                EmbeddingModel embeddingModel = embeddingModelFactory.getOrCreate(ragConfig);
                EmbeddingStore<TextSegment> store = embeddingStoreFactory.getOrCreate(ragConfig, kbName);

                ContentRetriever retriever = EmbeddingStoreContentRetriever.builder().embeddingStore(store).embeddingModel(embeddingModel)
                        .maxResults(maxResults).minScore(minScore).build();

                // Step 5: Retrieve
                List<Content> relevant = retriever.retrieve(Query.from(userQuery));

                LOGGER.infof("RAG retrieval from KB '%s': %d results (maxResults=%d, minScore=%.2f)", kbName, relevant.size(), maxResults, minScore);

                // Build trace entry
                Map<String, Object> traceEntry = new HashMap<>();
                traceEntry.put("kb", kbName);
                traceEntry.put("provider", ragConfig.getEmbeddingProvider());
                traceEntry.put("storeType", ragConfig.getStoreType());
                traceEntry.put("maxResults", maxResults);
                traceEntry.put("minScore", minScore);
                traceEntry.put("retrievedCount", relevant.size());
                traceEntries.add(traceEntry);

                allResults.addAll(relevant.stream().map(c -> new RetrievalResult(kbName, c)).toList());

            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to retrieve from KB '%s': %s", kbName, e.getMessage());

                Map<String, Object> errorTrace = new HashMap<>();
                errorTrace.put("kb", kbName);
                errorTrace.put("error", e.getMessage());
                traceEntries.add(errorTrace);
            }
        }

        // Step 6: Store audit trace in memory
        if (!traceEntries.isEmpty()) {
            var ragTraceData = dataFactory.createData("rag:trace:" + taskId, traceEntries);
            currentStep.storeData(ragTraceData);
        }

        if (allResults.isEmpty()) {
            return null;
        }

        // Step 7: Format context, bounded by the task's maxRagContextChars.
        // Finding F7: without this, every chunk from every matched knowledge base was
        // concatenated verbatim into the system prompt, which maxContextTokens
        // explicitly does not cover — with enableWorkflowRag across N knowledge bases
        // the prompt grew until the provider rejected the request.
        String formattedContext = formatRagContext(allResults, resolveMaxChars(task));

        // Store formatted context in memory for audit
        var ragContextData = dataFactory.createData("rag:context:" + taskId, formattedContext);
        currentStep.storeData(ragContextData);

        return formattedContext;
    }

    /**
     * Resolve the character cap for the assembled RAG block. {@code null},
     * {@code -1} or {@code 0} mean "unbounded" (the pre-F7 behavior).
     */
    static int resolveMaxChars(LlmConfiguration.Task task) {
        Integer configured = task != null ? task.getMaxRagContextChars() : null;
        return configured != null && configured > 0 ? configured : -1;
    }

    /**
     * Formats retrieval results into a structured context string for the LLM,
     * stopping once {@code maxChars} is reached.
     *
     * @param maxChars
     *            character ceiling for the whole block; {@code -1} = unbounded
     */
    static String formatRagContext(List<RetrievalResult> results, int maxChars) {
        StringBuilder sb = new StringBuilder();
        String currentKb = null;
        int omitted = 0;

        for (RetrievalResult result : results) {
            var chunk = new StringBuilder();
            if (!result.kbName().equals(currentKb)) {
                if (currentKb != null) {
                    chunk.append("\n");
                }
                chunk.append("### Source: ").append(result.kbName()).append("\n\n");
            }
            chunk.append(result.content().textSegment() != null && result.content().textSegment().text() != null
                    ? result.content().textSegment().text()
                    : "").append("\n\n");

            if (maxChars > 0 && sb.length() + chunk.length() > maxChars) {
                omitted++;
                continue;
            }
            if (!result.kbName().equals(currentKb)) {
                currentKb = result.kbName();
            }
            sb.append(chunk);
        }

        if (omitted > 0) {
            LOGGER.warnf("RAG context capped at %d chars — %d retrieved chunk(s) omitted. "
                    + "Raise maxRagContextChars or lower maxResults/knowledge base count.", maxChars, omitted);
            sb.append("\n[... ").append(omitted).append(" further retrieved passage(s) omitted: RAG context limit (")
                    .append(maxChars).append(" chars) reached ...]");
        }

        return sb.toString().trim();
    }
}
