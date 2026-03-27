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
                // Find matching reference (logically non-null here, but guard for null analysis)
                if (kbRefs == null) continue;
                var ref = kbRefs.stream().filter(r -> kbName.equals(r.getName())).findFirst().orElse(null);
                if (ref == null)
                    continue; // This KB not referenced by task

                maxResults = ref.getMaxResults() != null ? ref.getMaxResults() : ragConfig.getMaxResults();
                minScore = ref.getMinScore() != null ? ref.getMinScore() : ragConfig.getMinScore();
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

        // Step 7: Format context
        String formattedContext = formatRagContext(allResults);

        // Store formatted context in memory for audit
        var ragContextData = dataFactory.createData("rag:context:" + taskId, formattedContext);
        currentStep.storeData(ragContextData);

        return formattedContext;
    }

    /**
     * Formats retrieval results into a structured context string for the LLM.
     */
    private String formatRagContext(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        String currentKb = null;

        for (RetrievalResult result : results) {
            if (!result.kbName().equals(currentKb)) {
                if (currentKb != null) {
                    sb.append("\n");
                }
                sb.append("### Source: ").append(result.kbName()).append("\n\n");
                currentKb = result.kbName();
            }
            sb.append(result.content().textSegment() != null && result.content().textSegment().text() != null
                    ? result.content().textSegment().text()
                    : "").append("\n\n");
        }

        return sb.toString().trim();
    }
}
