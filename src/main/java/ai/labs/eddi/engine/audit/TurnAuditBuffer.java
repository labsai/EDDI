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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <b>What is redacted.</b> Every input form any entry of the turn recorded as
 * {@code userInput} (raw and normalized can differ) is removed from EVERY
 * buffered entry — including entries that record no input themselves, such as a
 * task-failure entry whose error message quotes the offending token, and
 * entries built after the property setter already replaced the recorded input.
 * Context values the client marked {@code "secret": true} are removed from
 * every entry the same way, whether or not the input was a secret — they are
 * replaced by {@link MemoryKeys#SECRET_CONTEXT_PLACEHOLDER} and the recorded
 * input is left as it is.
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
    /** Every non-placeholder input form this turn's entries recorded. */
    private final Set<String> recordedInputs = new LinkedHashSet<>();

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
        if (entry == null) {
            return;
        }
        entries.add(entry);
        if (entry.input() != null && entry.input().get(USER_INPUT) instanceof String recorded && !recorded.isEmpty()
                && !MemoryKeys.SECRET_INPUT_PLACEHOLDER.equals(recorded)) {
            recordedInputs.add(recorded);
        }
    }

    /**
     * Restore the original collector and submit every buffered entry to it, in
     * order. A failing submit is logged and does not stop the rest.
     */
    public void flush(IConversationMemory memory) {
        flush(memory, List.of());
    }

    /**
     * {@link #flush(IConversationMemory)}, additionally removing
     * {@code secretContextValues} from every entry.
     *
     * @param secretContextValues
     *            the turn's secret context values to redact, longest first; the
     *            caller has already dropped values too short to search for
     */
    public void flush(IConversationMemory memory, List<String> secretContextValues) {
        memory.setAuditCollector(delegate);
        List<AuditEntry> pending;
        Set<String> inputs;
        synchronized (this) {
            pending = List.copyOf(entries);
            inputs = Set.copyOf(recordedInputs);
            entries.clear();
            recordedInputs.clear();
        }
        boolean secretInput = inputWasScrubbed(memory);
        for (AuditEntry entry : pending) {
            try {
                AuditEntry submitted = secretInput ? redact(entry, inputs) : entry;
                if (secretContextValues != null && !secretContextValues.isEmpty()) {
                    submitted = redactValues(submitted, secretContextValues, MemoryKeys.SECRET_CONTEXT_PLACEHOLDER);
                }
                delegate.collect(submitted);
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
     * The entry with any recorded user input replaced by the placeholder and every
     * occurrence of {@code secretInputs} (those of at least
     * {@link #MIN_REDACTED_INPUT_LENGTH} characters) removed from all four payload
     * maps. Longer inputs are replaced first, so a normalized form contained in the
     * raw one cannot leave a fragment behind.
     */
    static AuditEntry redact(AuditEntry entry, Set<String> secretInputs) {
        List<String> needles = secretInputs.stream()
                .filter(input -> input.length() >= MIN_REDACTED_INPUT_LENGTH)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        Map<String, Object> input = entry.input() != null ? new LinkedHashMap<>(entry.input()) : null;
        if (input != null && input.containsKey(USER_INPUT)) {
            input.put(USER_INPUT, MemoryKeys.SECRET_INPUT_PLACEHOLDER);
        }
        return withRedactedPayload(entry, input, needles, MemoryKeys.SECRET_INPUT_PLACEHOLDER);
    }

    /**
     * The entry with every occurrence of {@code needles} (searched in the given
     * order) replaced by {@code placeholder} in all four payload maps.
     */
    static AuditEntry redactValues(AuditEntry entry, List<String> needles, String placeholder) {
        return withRedactedPayload(entry, entry.input(), needles, placeholder);
    }

    private static AuditEntry withRedactedPayload(AuditEntry entry, Map<String, Object> input, List<String> needles, String placeholder) {
        return entry.withPayload(redactMap(input, needles, placeholder), redactMap(entry.output(), needles, placeholder),
                redactMap(entry.llmDetail(), needles, placeholder), redactMap(entry.toolCalls(), needles, placeholder));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redactMap(Map<String, Object> map, List<String> needles, String placeholder) {
        return map == null || needles.isEmpty() ? map : (Map<String, Object>) redactValue(map, needles, placeholder);
    }

    private static Object redactValue(Object value, List<String> needles, String placeholder) {
        if (value instanceof String text) {
            String redacted = text;
            for (String needle : needles) {
                redacted = redacted.replace(needle, placeholder);
            }
            return redacted;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), redactValue(nested, needles, placeholder)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(nested -> redactValue(nested, needles, placeholder)).toList();
        }
        return value;
    }
}
