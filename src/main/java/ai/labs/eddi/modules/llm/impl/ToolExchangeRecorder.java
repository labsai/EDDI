/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tool exchange of ONE tool-loop run, recorded as it happens rather than
 * handed back when the run returns — so a caller that abandons the run (a
 * cascade step that timed out or threw) can still see which tools completed.
 * <p>
 * Holds only complete pairs: a tool call is recorded together with its result,
 * never before it. An assistant message that asked for several tools is
 * reported with the calls that have a result so far and nothing else, so a run
 * cancelled between two tools of one batch never leaves a dangling call — which
 * every provider rejects, and which would hide whether that call ran.
 * <p>
 * Thread-safe: the loop records on the step's virtual thread while the cascade
 * reads from the pipeline thread after cancelling it. One instance per run; it
 * is never shared across conversations, so {@link ToolLoopRunner} stays
 * stateless.
 */
final class ToolExchangeRecorder {

    /** One assistant message and the calls of it that have completed. */
    private record Round(AiMessage call, List<ToolExecutionRequest> requests, List<ToolExecutionResultMessage> results) {
    }

    private final List<Round> rounds = new ArrayList<>();

    /**
     * The prices of the model this run is on, or null for the task's own. Set by a
     * cascade, whose step model is not the task's: the loop prices its mid-loop
     * ceiling, its pause spend and a failure's spend with them.
     */
    private final ModelPrices prices;

    /** Token usage of the model rounds completed so far. */
    private TokenUsage usage;

    ToolExchangeRecorder() {
        this(null);
    }

    ToolExchangeRecorder(ModelPrices prices) {
        this.prices = prices;
    }

    /** The prices this run is priced at, or null for the task's own. */
    ModelPrices prices() {
        return prices;
    }

    /**
     * Records a completed model round's token usage — so a caller that abandons the
     * run (a timed-out cascade step) can still price the rounds it paid for.
     */
    synchronized void recordUsage(TokenUsage roundUsage) {
        if (roundUsage != null) {
            usage = ToolContextBudget.sumTokens(usage, roundUsage);
        }
    }

    /**
     * Token usage of the rounds completed so far, as a {@code tokenUsage} map;
     * empty when none.
     */
    synchronized Map<String, Object> tokenUsage() {
        return usage != null ? ToolContextBudget.tokenUsageMap(usage) : Map.of();
    }

    /**
     * Records that {@code request}, one of the calls {@code call} asked for, has
     * produced {@code result} — executed, refused or denied alike, since the model
     * saw an answer for it either way.
     */
    synchronized void record(AiMessage call, ToolExecutionRequest request, ToolExecutionResultMessage result) {
        Round last = rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
        if (last == null || last.call() != call) {
            last = new Round(call, new ArrayList<>(), new ArrayList<>());
            rounds.add(last);
        }
        last.requests().add(request);
        last.results().add(result);
    }

    /**
     * The completed exchange so far, in order, in the shape
     * {@link ToolLoopRunner#toolExchange} produces (every call carries an id), so
     * it can be carried to a different provider exactly like a returned one.
     */
    synchronized List<ChatMessage> completedExchange() {
        List<ChatMessage> transcript = new ArrayList<>();
        for (Round round : rounds) {
            transcript.add(round.requests().equals(round.call().toolExecutionRequests())
                    ? round.call()
                    : round.call().toBuilder().toolExecutionRequests(List.copyOf(round.requests())).build());
            transcript.addAll(round.results());
        }
        return ToolLoopRunner.toolExchange(transcript);
    }
}
