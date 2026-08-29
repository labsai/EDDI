/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.modules.llm.impl.PromptSnippetService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
    private final ConversationAccessGuard conversationAccessGuard;

    private final ResourceAccessGuard resourceAccessGuard;

    /** Stand-in for a snippet body the caller may not read. */
    private static final String REDACTED = "<redacted>";

    @Inject
    public RestTemplatePreview(ITemplatingEngine templatingEngine,
            IConversationMemoryStore conversationMemoryStore,
            IMemoryItemConverter memoryItemConverter,
            PromptSnippetService promptSnippetService,
            ConversationAccessGuard conversationAccessGuard,
            ResourceAccessGuard resourceAccessGuard) {
        this.resourceAccessGuard = resourceAccessGuard;
        this.templatingEngine = templatingEngine;
        this.conversationMemoryStore = conversationMemoryStore;
        this.memoryItemConverter = memoryItemConverter;
        this.promptSnippetService = promptSnippetService;
        this.conversationAccessGuard = conversationAccessGuard;
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

        // Inject prompt snippets — same as LlmTask.execute(), except that a caller
        // who does not see everything gets the NAMES with the contents replaced.
        //
        // The redaction has to happen here, in the map the template is rendered
        // against, and not only in the reference panel below. The caller supplies
        // the template, so redacting the panel alone is no protection at all: one
        // call lists every snippet name, and a second call whose template is
        // "{snippets.<name>}" prints the content the panel refused to show. That
        // was a live hole, not a hypothetical — snippets are a guarded
        // configuration type, and this endpoint would otherwise hand any editor
        // the full text of every colleague's prompt building blocks.
        Map<String, Object> snippets = promptSnippetService.getAll();
        boolean redactSnippets = !resourceAccessGuard.seesEverything();
        if (!snippets.isEmpty()) {
            templateData.put("snippets", redactSnippets ? redactValues(snippets) : snippets);
        }

        // Flatten keys for the variable reference panel
        List<String> availableVariables = new ArrayList<>();
        Map<String, Object> variableValues = new LinkedHashMap<>();
        flattenKeys("", templateData, availableVariables, variableValues, 4);

        // Belt and braces: the panel is flattened from the already-redacted map, so
        // this only catches a snippet whose own value is a nested structure that
        // flattening walked into.
        if (!snippets.isEmpty() && redactSnippets) {
            variableValues.replaceAll((key, value) -> key.startsWith("snippets.") ? REDACTED : value);
        }

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
     * The same snippet names with every value replaced.
     * <p>
     * Names are kept because a preview that cannot tell you which
     * {@code {snippets.x}} references resolve is not much of a preview, and a name
     * is not the secret — the content is. Rendering then yields {@code <redacted>}
     * where the content would have been, which is the honest answer rather than a
     * silent blank.
     */
    private static Map<String, Object> redactValues(Map<String, Object> snippets) {
        Map<String, Object> redacted = new LinkedHashMap<>(snippets.size());
        snippets.keySet().forEach(key -> redacted.put(key, REDACTED));
        return redacted;
    }

    /**
     * Load real conversation memory and convert it to the template data map
     * (identical to what {@code LlmTask} uses at runtime).
     * <p>
     * The ownership check runs <em>before</em> the snapshot is read: the response
     * echoes back the flattened variable values, so a preview against a foreign
     * conversationId would otherwise dump that conversation's properties, context
     * and memory to the caller. A {@code ForbiddenException} from the guard is
     * deliberately not caught here: it must surface as a 403 rather than be
     * degraded into the "conversation not found" response below, which would mask a
     * genuine authorization failure and hide it from the operator. (Collapsing 403
     * into 404 would in fact disclose <em>less</em> — it is distinguishing the two
     * that reveals which conversations exist — but this endpoint is already
     * restricted to admins and editors, so an honest authorization signal is worth
     * more here than that marginal reduction.)
     */
    private Map<String, Object> loadConversationData(String conversationId) {
        conversationAccessGuard.requireExistingConversationOwner(conversationId);
        try {
            var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            var memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);
            return memoryItemConverter.convert(memory);
        } catch (IResourceStore.ResourceNotFoundException e) {
            // Genuinely absent — the caller reports this as "conversation not found".
            LOGGER.debugv("No conversation to preview against: {0}", sanitize(conversationId));
            return null;
        } catch (IResourceStore.ResourceStoreException e) {
            // A store failure is NOT a missing conversation. Collapsing the two told an
            // operator mid-outage that their conversation did not exist, sending them to
            // look for the wrong problem entirely. Surface it as a server error, and keep
            // the driver detail in the log rather than the response body (finding A12).
            String correlationId = UUID.randomUUID().toString();
            LOGGER.errorv(e, "Template preview could not load conversation {0} (correlationId: {1})",
                    sanitize(conversationId), correlationId);
            throw new InternalServerErrorException("Could not load conversation (correlationId: " + correlationId + ")");
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
