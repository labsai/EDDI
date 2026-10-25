/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code OUTPUT_MATCH} side of the turn hook, called on the turn's thread
 * for a turn that finished cleanly.
 * <p>
 * <b>Cheap by construction.</b> First an in-memory cache lookup: an agent
 * without enabled OUTPUT_MATCH rules returns right there, having touched
 * nothing of the conversation. Otherwise only what the rules read is copied —
 * the named properties (as plain values, through {@code AlertJson}), the output
 * text (at most {@link TurnSnapshot#MAX_OUTPUT_CHARS}), the action list — into
 * a {@link TurnSnapshot}, and matching runs on the alert worker. The live
 * memory never leaves the turn. On a cache miss the rules are loaded on the
 * worker and this one turn copies everything a rule could read, so nothing is
 * missed.
 */
@ApplicationScoped
public class OutputMatchHook {

    private static final Logger LOGGER = Logger.getLogger(OutputMatchHook.class);

    private final AlertRuleCache ruleCache;
    private final AlertDispatcher dispatcher;
    private final AlertEngine engine;

    @Inject
    public OutputMatchHook(AlertRuleCache ruleCache, AlertDispatcher dispatcher, AlertEngine engine) {
        this.ruleCache = ruleCache;
        this.dispatcher = dispatcher;
        this.engine = engine;
    }

    /** Never throws into the turn. */
    public void onCleanTurn(IConversationMemory memory) {
        try {
            String agentId = memory.getAgentId();
            if (agentId == null) {
                return;
            }
            Optional<OutputNeeds> cached = ruleCache.cachedOutputNeeds(agentId);
            if (cached.isPresent() && !cached.get().any()) {
                return;
            }
            OutputNeeds needs;
            if (cached.isPresent()) {
                needs = cached.get();
            } else {
                if (!dispatcher.submit(() -> ruleCache.loadOutputNeeds(agentId))) {
                    // Alerts are off or the queue is full: the evaluation would be
                    // dropped too, so copy nothing from the turn.
                    return;
                }
                needs = OutputNeeds.ALL;
            }
            TurnSnapshot snapshot = snapshot(memory, needs);
            dispatcher.submit(() -> engine.evaluateOutput(snapshot));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not hand a turn to the output-match alerts: %s", e.getMessage());
        }
    }

    static TurnSnapshot snapshot(IConversationMemory memory, OutputNeeds needs) {
        Map<String, Object> properties = new HashMap<>();
        if (needs.properties() == null || !needs.properties().isEmpty()) {
            IConversationMemory.IConversationProperties live = memory.getConversationProperties();
            if (live != null) {
                Map<String, Object> raw = live.toMap();
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    if (needs.properties() == null || needs.properties().contains(entry.getKey())) {
                        Object value = detach(entry.getValue());
                        if (value != null) {
                            properties.put(entry.getKey(), value);
                        }
                    }
                }
            }
        }
        String output = null;
        List<String> actions = null;
        IConversationMemory.IWritableConversationStep step = memory.getCurrentStep();
        if (step != null) {
            if (needs.output()) {
                String text = ConversationOutputExtractor.extractText(step.getConversationOutput());
                output = text == null || text.length() > TurnSnapshot.MAX_OUTPUT_CHARS ? null : text;
            }
            if (needs.actions()) {
                IData<List<String>> data = step.getLatestData(MemoryKeys.ACTIONS);
                actions = data == null || data.getResult() == null ? List.of() : List.copyOf(data.getResult());
            }
        }
        // The owner travels only to be recorded with a stored value, for GDPR erasure;
        // it is never shown or sent.
        return new TurnSnapshot(memory.getConversationId(), memory.getAgentId(), memory.getAgentVersion(), Map.copyOf(properties), output,
                actions, memory.getUserId(), Instant.now());
    }

    /**
     * A property value as plain, immutable-enough data — never a live object of the
     * turn.
     */
    private static Object detach(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        try {
            return AlertJson.convert(value, Object.class);
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }
}
