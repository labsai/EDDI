/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.modules.llm.impl.PromptSnippetService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.prepareContext;
import static ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import static ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class MemoryItemConverter implements IMemoryItemConverter {
    private static final Logger LOGGER = Logger.getLogger(MemoryItemConverter.class);
    private static final String KEY_MEMORY = "memory";
    private static final String KEY_SNIPPETS = "snippets";
    private static final String KEY_VARS = "vars";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_LAST = "last";
    private static final String KEY_PAST = "past";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_USER_INFO = "userInfo";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_CONVERSATION_INFO = "conversationInfo";
    private static final String KEY_CONVERSATION_ID = "conversationId";
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_AGENT_VERSION = "agentVersion";
    private static final String KEY_CONVERSATION_LOG = "conversationLog";

    /**
     * Prompt snippets, exposed as {@code {snippets.<name>}}. Injected here — not
     * only in {@code LlmTask} — so the documented template data model actually
     * holds: output sets, httpCall bodies and property instructions resolve the
     * namespace instead of silently rendering it empty.
     * <p>
     * Both services are Caffeine-cached, so this is a cache lookup per
     * {@link #convert} call, not a store round-trip. Field injection (rather than
     * constructor injection) keeps the converter usable as a plain
     * {@code new MemoryItemConverter()} in tests, where the namespaces are simply
     * absent.
     */
    @Inject
    PromptSnippetService promptSnippetService;

    /** Global variables, exposed as {@code {vars.<key>}}. See above. */
    @Inject
    GlobalVariableResolver globalVariableResolver;

    @Override
    public Map<String, Object> convert(IConversationMemory memory) {
        Map<String, Object> conversationDataObjects = new LinkedHashMap<>();
        List<IData<Context>> contextDataList = memory.getCurrentStep().getAllData(KEY_CONTEXT);
        var contextMap = prepareContext(contextDataList);
        var memoryMap = convertMemoryItems(memory);
        var conversationProperties = memory.getConversationProperties();

        if (!contextMap.isEmpty()) {
            conversationDataObjects.put(KEY_CONTEXT, contextMap);
            conversationDataObjects.putAll(contextMap);
        }

        if (!conversationProperties.isEmpty()) {
            conversationDataObjects.put(KEY_PROPERTIES, conversationProperties.toMap());
        }

        if (!memoryMap.isEmpty()) {
            conversationDataObjects.put(KEY_MEMORY, convertMemoryItems(memory));
        }

        addSnippetsAndVars(conversationDataObjects);

        addInfoObject(conversationDataObjects, memory.getUserId(), KEY_USER_INFO, KEY_USER_ID);
        addInfoObject(conversationDataObjects, memory.getConversationId(), KEY_CONVERSATION_INFO, KEY_CONVERSATION_ID);
        addInfoObject(conversationDataObjects, memory.getAgentId(), KEY_CONVERSATION_INFO, KEY_AGENT_ID);
        addInfoObject(conversationDataObjects, memory.getAgentVersion().toString(), KEY_CONVERSATION_INFO, KEY_AGENT_VERSION);

        conversationDataObjects.put(KEY_CONVERSATION_LOG, new ConversationLogGenerator(memory));

        return conversationDataObjects;
    }

    /**
     * Adds the {@code snippets} and {@code vars} namespaces. Never lets a snippet /
     * variable lookup failure break a conversation turn — a missing namespace
     * renders empty, exactly as it did before it was injected here.
     * <p>
     * <strong>Never overwrites an existing key.</strong> The context map is
     * flattened onto the top level before this runs, so a plain {@code put} let a
     * deployment-wide global-variable set silently replace a client-supplied
     * context variable literally named {@code vars} (or {@code snippets}) — a
     * template that resolved the caller's data before this namespace was injected
     * would suddenly resolve the deployment's. Explicit per-turn context wins, as
     * it always did.
     */
    private void addSnippetsAndVars(Map<String, Object> conversationDataObjects) {
        if (promptSnippetService != null) {
            try {
                Map<String, Object> snippets = promptSnippetService.getAll();
                if (!snippets.isEmpty()) {
                    putNamespaceIfAbsent(conversationDataObjects, KEY_SNIPPETS, snippets);
                }
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not resolve prompt snippets for template data: %s", LogSanitizer.sanitize(e.getMessage()));
            }
        }

        if (globalVariableResolver != null) {
            try {
                Map<String, Object> globalVars = globalVariableResolver.getTemplateData();
                if (!globalVars.isEmpty()) {
                    putNamespaceIfAbsent(conversationDataObjects, KEY_VARS, globalVars);
                }
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not resolve global variables for template data: %s", LogSanitizer.sanitize(e.getMessage()));
            }
        }
    }

    private static void putNamespaceIfAbsent(Map<String, Object> conversationDataObjects, String key, Map<String, Object> namespace) {
        if (conversationDataObjects.putIfAbsent(key, namespace) != null) {
            LOGGER.warnf("Template namespace '%s' is shadowed by a context variable of the same name — "
                    + "the context value wins; rename the context variable to reach the '%s' namespace.", key, key);
        }
    }

    private void addInfoObject(Map<String, Object> ret, String id, String keyInfo, String keyId) {
        if (!isNullOrEmpty(id)) {
            if (ret.containsKey(keyInfo)) {
                Object o = ret.get(keyInfo);
                if (o instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    var map = (Map<String, Object>) o;
                    map.put(keyId, id);
                }
            } else {
                var objectMap = new HashMap<String, Object>();
                objectMap.put(keyId, id);
                ret.put(keyInfo, objectMap);
            }
        }
    }

    private static Map<String, Object> convertMemoryItems(IConversationMemory memory) {
        Map<String, Object> props = new HashMap<>();
        IWritableConversationStep currentStep;
        IConversationStep lastStep;

        currentStep = memory.getCurrentStep();
        var current = currentStep.getConversationOutput();
        props.put(KEY_CURRENT, current);

        var last = new ConversationOutput();
        if (memory.getPreviousSteps().size() > 0) {
            lastStep = memory.getPreviousSteps().get(0);
            last = lastStep.getConversationOutput();
        }
        props.put(KEY_LAST, last);

        var past = memory.getConversationOutputs();
        if (past.size() > 1) {
            past = past.subList(1, past.size());
        } else {
            past = new LinkedList<>();
        }

        props.put(KEY_PAST, past);

        return props;
    }
}
