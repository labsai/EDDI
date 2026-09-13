/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Holds a turn's audit entries until the turn's pipeline has finished, then
 * submits them — with the user input redacted if the turn turned out to carry a
 * secret.
 * <p>
 * <b>Why the entries cannot go out one by one.</b> {@code LifecycleManager}
 * builds an entry after every task, and each one records the user input. A
 * {@code scope: "secret"} property is vaulted by {@code PropertySetterTask},
 * which runs AFTER the parser and the behavior rules — so their entries,
 * carrying the plaintext, had already been submitted to the append-only, signed
 * ledger by the time the input was known to be a secret. The conversation
 * document was scrubbed; the ledger kept the key forever.
 * <p>
 * Flushing at the end of the turn means that decision is known before anything
 * is written. The trade-off is that entries of a turn that never finishes (the
 * process dies mid-pipeline) are not written — the same turn's conversation
 * state is not persisted either, so the ledger and the document still agree.
 * <p>
 * Not thread-safe beyond what a single turn needs: the pipeline runs its tasks
 * sequentially, but {@link #collect} is synchronized anyway because a buffer
 * that silently loses an entry is the worst failure an audit component can
 * have.
 */
public final class TurnAuditBuffer implements IAuditEntryCollector {

    private static final Logger LOGGER = Logger.getLogger(TurnAuditBuffer.class);

    /**
     * Shorter inputs are not searched for elsewhere in an entry: replacing every
     * "ok" in a model response would destroy the record for no gain.
     */
    static final int MIN_REDACTED_INPUT_LENGTH = 4;

    private static final String USER_INPUT = "userInput";

    private final IAuditEntryCollector delegate;
    private final List<AuditEntry> entries = new ArrayList<>();

    private TurnAuditBuffer(IAuditEntryCollector delegate) {
        this.delegate = delegate;
    }

    /**
     * Put a buffer in front of the memory's audit collector for the duration of a
     * turn.
     *
     * @return the installed buffer — pass it to {@link #flush} when the turn ends —
     *         or {@code null} when auditing is off (or a buffer is already
     *         installed, so a nested call never flushes early)
     */
    public static TurnAuditBuffer install(IConversationMemory memory) {
        IAuditEntryCollector current = memory.getAuditCollector();
        if (current == null || current instanceof TurnAuditBuffer) {
            return null;
        }
        var buffer = new TurnAuditBuffer(current);
        memory.setAuditCollector(buffer);
        return buffer;
    }

    @Override
    public synchronized void collect(AuditEntry entry) {
        if (entry != null) {
            entries.add(entry);
        }
    }

    /**
     * Restore the original collector and submit every buffered entry to it, in
     * order. A failing submit is logged and does not stop the rest.
     */
    public void flush(IConversationMemory memory) {
        memory.setAuditCollector(delegate);
        List<AuditEntry> pending;
        synchronized (this) {
            pending = List.copyOf(entries);
            entries.clear();
        }
        boolean secretInput = inputWasScrubbed(memory);
        for (AuditEntry entry : pending) {
            try {
                delegate.collect(secretInput ? redactUserInput(entry) : entry);
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Audit entry for task '%s' of conversation '%s' could not be submitted",
                        sanitize(entry.taskId()), sanitize(entry.conversationId()));
            }
        }
    }

    private static boolean inputWasScrubbed(IConversationMemory memory) {
        var step = memory.getCurrentStep();
        if (step == null) {
            return false;
        }
        IData<String> initial = step.getLatestData(MemoryKeys.INPUT_INITIAL.key());
        return initial != null && MemoryKeys.SECRET_INPUT_PLACEHOLDER.equals(initial.getResult());
    }

    /**
     * The entry with its recorded user input replaced by the placeholder, and every
     * other copy of that input (a compiled prompt, a tool argument) removed from
     * the rest of its payload.
     */
    static AuditEntry redactUserInput(AuditEntry entry) {
        Map<String, Object> input = entry.input();
        Object recorded = input != null ? input.get(USER_INPUT) : null;
        if (!(recorded instanceof String raw) || raw.isEmpty() || MemoryKeys.SECRET_INPUT_PLACEHOLDER.equals(raw)) {
            return entry;
        }
        Map<String, Object> redactedInput = new LinkedHashMap<>(input);
        redactedInput.put(USER_INPUT, MemoryKeys.SECRET_INPUT_PLACEHOLDER);
        if (raw.length() < MIN_REDACTED_INPUT_LENGTH) {
            return entry.withPayload(redactedInput, entry.output(), entry.llmDetail(), entry.toolCalls());
        }
        return entry.withPayload(redactedInput, redactMap(entry.output(), raw), redactMap(entry.llmDetail(), raw),
                redactMap(entry.toolCalls(), raw));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redactMap(Map<String, Object> map, String raw) {
        return map == null ? null : (Map<String, Object>) redactValue(map, raw);
    }

    private static Object redactValue(Object value, String raw) {
        if (value instanceof String text) {
            return text.contains(raw) ? text.replace(raw, MemoryKeys.SECRET_INPUT_PLACEHOLDER) : text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), redactValue(nested, raw)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(nested -> redactValue(nested, raw)).toList();
        }
        return value;
    }
}
