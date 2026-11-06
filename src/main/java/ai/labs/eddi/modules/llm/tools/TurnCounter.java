/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A count that lasts one conversation turn, behind the per-turn budgets of the
 * LLM tools ({@code maxWritesPerTurn}, {@code maxDelegationsPerTask}).
 * <p>
 * A turn assembles its tools more than once: each step of an agent-mode model
 * cascade builds a fresh tool set, and so does the continuation of a turn that
 * resumed after a HITL tool approval. A count held in a field of the tool
 * restarted at zero on each of them, so a three-step cascade was allowed three
 * times the configured budget. {@link #onStep} keeps the count on the
 * conversation's current step instead — where {@code hitl:tool_pause_count}
 * already lives, for the same reason — so every tool instance of the turn reads
 * and adds to one number, and the next turn's step starts again from zero.
 * <p>
 * The key is read by exact match ({@link IWritableConversationStep#getData}),
 * not by prefix: {@code getLatestData} would let {@code tools:delegations:a}
 * read the count of {@code tools:delegations:ab}. Callers use keys outside the
 * public step prefixes, so the count never reaches a client's conversation
 * snapshot. The tool calls of a turn are dispatched one at a time, so the
 * read-then-write of {@link #increment} is not raced by another tool call.
 */
public final class TurnCounter {

    private final IConversationMemory memory;
    private final String key;

    /**
     * Used when there is no memory to count on (direct construction), or no current
     * step — the budget then bounds this instance, as it used to, rather than
     * nothing.
     */
    private final AtomicInteger local = new AtomicInteger();

    private TurnCounter(IConversationMemory memory, String key) {
        this.memory = memory;
        this.key = key;
    }

    /**
     * A count kept on the current step of {@code memory} under {@code key}, shared
     * by every counter built for the same key during the turn.
     */
    public static TurnCounter onStep(IConversationMemory memory, String key) {
        return new TurnCounter(Objects.requireNonNull(memory, "memory"), Objects.requireNonNull(key, "key"));
    }

    /**
     * A count local to this instance, for a tool constructed without a
     * conversation.
     */
    public static TurnCounter local() {
        return new TurnCounter(null, null);
    }

    /** The count so far this turn; 0 when nothing has been counted yet. */
    public int get() {
        IWritableConversationStep step = currentStep();
        if (step == null) {
            return local.get();
        }
        IData<Object> data = step.getData(key);
        // Number, not Integer: a step reloaded from the store may hand back another
        // numeric type for the same value.
        return data != null && data.getResult() instanceof Number count ? count.intValue() : 0;
    }

    /**
     * Adds one to the count.
     *
     * @return the count including this one
     */
    public int increment() {
        IWritableConversationStep step = currentStep();
        if (step == null) {
            return local.incrementAndGet();
        }
        int next = get() + 1;
        step.storeData(new Data<>(key, next));
        return next;
    }

    private IWritableConversationStep currentStep() {
        return memory != null ? memory.getCurrentStep() : null;
    }
}
