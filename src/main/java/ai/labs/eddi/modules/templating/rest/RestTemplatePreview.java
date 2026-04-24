/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.rest;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.llm.impl.PromptSnippetService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Implementation of the template preview REST endpoint.
 * <p>
 * Uses the real Qute {@link ITemplatingEngine} to resolve templates against
 * either a real conversation's memory data or built-in sample defaults.
 * Includes all prompt snippets from {@link PromptSnippetService}.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class RestTemplatePreview implements IRestTemplatePreview {

    private static final Logger LOGGER = Logger.getLogger(RestTemplatePreview.class);

    private final ITemplatingEngine templatingEngine;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IMemoryItemConverter memoryItemConverter;
    private final PromptSnippetService promptSnippetService;

    @Inject
    public RestTemplatePreview(ITemplatingEngine templatingEngine,
            IConversationMemoryStore conversationMemoryStore,
            IMemoryItemConverter memoryItemConverter,
            PromptSnippetService promptSnippetService) {
        this.templatingEngine = templatingEngine;
        this.conversationMemoryStore = conversationMemoryStore;
        this.memoryItemConverter = memoryItemConverter;
        this.promptSnippetService = promptSnippetService;
    }

    @Override
    public TemplatePreviewResponse previewTemplate(TemplatePreviewRequest request) {
        if (request == null || request.template() == null || request.template().isBlank()) {
            return new TemplatePreviewResponse("", List.of(), Map.of(), null);
        }

        Map<String, Object> templateData;

        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            templateData = loadConversationData(request.conversationId());
            if (templateData == null) {
                return new TemplatePreviewResponse(null, List.of(), Map.of(),
                        "Conversation not found: " + request.conversationId());
            }
        } else {
            templateData = buildDefaultSampleData();
        }

        // Inject prompt snippets — same as LlmTask.execute()
        Map<String, Object> snippets = promptSnippetService.getAll();
        if (!snippets.isEmpty()) {
            templateData.put("snippets", snippets);
        }

        // Flatten keys for the variable reference panel
        List<String> availableVariables = new ArrayList<>();
        Map<String, Object> variableValues = new LinkedHashMap<>();
        flattenKeys("", templateData, availableVariables, variableValues, 4);

        // Resolve template
        try {
            String resolved = templatingEngine.processTemplate(request.template(), templateData);
            return new TemplatePreviewResponse(resolved, availableVariables, variableValues, null);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.debugv("Template preview resolution error: {0}", e.getMessage());
            return new TemplatePreviewResponse(null, availableVariables, variableValues, e.getMessage());
        }
    }

    /**
     * Load real conversation memory and convert it to the template data map
     * (identical to what {@code LlmTask} uses at runtime).
     */
    private Map<String, Object> loadConversationData(String conversationId) {
        try {
            var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            var memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);
            return memoryItemConverter.convert(memory);
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            LOGGER.warnv("Could not load conversation for template preview: {0}", e.getMessage());
            return null;
        }
    }

    /**
     * Build a reasonable default data structure matching what
     * {@link IMemoryItemConverter#convert} produces at runtime. Provides realistic
     * sample values so users can preview templates without needing an active
     * conversation.
     */
    private static Map<String, Object> buildDefaultSampleData() {
        Map<String, Object> data = new LinkedHashMap<>();

        // properties
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("userName", "Alice");
        properties.put("language", "en");
        properties.put("email", "alice@example.com");
        data.put("properties", properties);

        // memory.current / memory.last / memory.past
        Map<String, Object> memory = new LinkedHashMap<>();
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("input", "What is my order status?");
        current.put("actions", List.of("check_order", "respond"));
        memory.put("current", current);

        Map<String, Object> last = new LinkedHashMap<>();
        last.put("input", "Hello");
        last.put("output", "Welcome! How can I help you today?");
        memory.put("last", last);

        memory.put("past", List.of(Map.of("input", "Hi", "output", "Hello!")));
        data.put("memory", memory);

        // context
        data.put("context", Map.of("output", "Previous context value"));

        // userInfo
        data.put("userInfo", Map.of("userId", "user-12345"));

        // conversationInfo
        Map<String, Object> convInfo = new LinkedHashMap<>();
        convInfo.put("conversationId", "conv-67890");
        convInfo.put("agentId", "agent-abc");
        convInfo.put("agentVersion", "1");
        data.put("conversationInfo", convInfo);

        // input (top-level, from context)
        data.put("input", "What is my order status?");

        return data;
    }

    /**
     * Recursively flatten a nested map into dot-separated key paths.
     *
     * @param prefix
     *            current key prefix
     * @param map
     *            the map to flatten
     * @param keys
     *            output list of dot-path keys
     * @param values
     *            output map of dot-path → value
     * @param maxDepth
     *            maximum recursion depth to prevent stack overflow
     */
    @SuppressWarnings("unchecked")
    private static void flattenKeys(String prefix, Map<String, Object> map,
                                    List<String> keys, Map<String, Object> values, int maxDepth) {
        if (maxDepth <= 0)
            return;
        for (var entry : map.entrySet()) {
            String fullKey = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                flattenKeys(fullKey, (Map<String, Object>) nested, keys, values, maxDepth - 1);
            } else {
                keys.add(fullKey);
                // Truncate long values for display
                if (value instanceof String s && s.length() > 200) {
                    values.put(fullKey, s.substring(0, 200) + "…");
                } else if (value instanceof List<?> list) {
                    values.put(fullKey, list.size() + " items");
                } else {
                    values.put(fullKey, value);
                }
            }
        }
    }
}
