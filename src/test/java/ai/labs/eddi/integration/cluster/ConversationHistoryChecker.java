/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks the per-conversation turn histories a cluster run produced against
 * what its clients observed, in the spirit of a Knossos/Elle history checker
 * reduced to EDDI's ordering contract (docs/clustering.md, "Ordering
 * contract").
 * <p>
 * Three sources are combined:
 * <ul>
 * <li>what each client <b>sent</b> and <b>was answered</b> ({@link Turn}),</li>
 * <li>the <b>committed</b> history: the stored user inputs of each
 * conversation, read back from the database through the REST API, in step
 * order,</li>
 * <li>what the LLM <b>saw</b>: the mock LLM's request log ({@link LlmCall}),
 * with the start and end of each call and the user message that preceded the
 * turn's own in the prompt.</li>
 * </ul>
 * Every input a client sends must be unique across the run (the suite prefixes
 * each with a conversation tag and a sequence number), which is what lets an
 * input identify its turn.
 * <p>
 * The violations, each a breach of a sentence in the contract:
 * <ul>
 * <li>{@link Kind#LOST}: the client was answered 200, the turn is not in the
 * committed history. ("none is ... lost")</li>
 * <li>{@link Kind#DUPLICATED}: one input committed more than once (a replay or
 * a retry that ran twice).</li>
 * <li>{@link Kind#PHANTOM}: a committed input no client sent.</li>
 * <li>{@link Kind#REFUSED_BUT_COMMITTED}: answered 409/503 ("its input was not
 * consumed") and committed anyway, so a client that retries runs it twice.</li>
 * <li>{@link Kind#OUT_OF_ORDER}: turn A was answered before turn B was sent,
 * both committed, and B is stored before A (real-time order, which subsumes
 * program order of a sequential client).</li>
 * <li>{@link Kind#OVERLAP}: the LLM calls of two committed turns of one
 * conversation overlapped in time ("never overlap anywhere").</li>
 * <li>{@link Kind#STALE_CONTEXT}: a committed turn's LLM call did not carry the
 * immediately preceding committed turn as its previous user message ("each turn
 * runs on, and commits on top of, every turn committed before it").</li>
 * </ul>
 * Calls of turns that never committed (a node killed or frozen mid-turn, a turn
 * fenced off) may overlap committed ones without that being a violation: once a
 * lease is gone, the fence, not mutual exclusion, is what keeps the history
 * sound. They are counted as {@link Report#zombieOverlaps()} for the record.
 */
public final class ConversationHistoryChecker {

    /** What the client concluded from the answer. */
    public enum Outcome {
        /** HTTP 200: the turn ran and must be committed exactly once. */
        OK,
        /**
         * HTTP 409 or 503: the server says the input was not consumed; it must not be
         * committed.
         */
        REFUSED,
        /**
         * No answer, a reset, a timeout or another error: committed at most once,
         * either way is fine.
         */
        INDETERMINATE;

        public static Outcome ofStatus(int status) {
            if (status == 200) {
                return OK;
            }
            if (status == 409 || status == 503) {
                return REFUSED;
            }
            return INDETERMINATE;
        }
    }

    /**
     * One request a client sent.
     *
     * @param conversationId
     *            the conversation it was sent to
     * @param input
     *            the user input, unique across the run
     * @param node
     *            the node it was sent to (for the report only)
     * @param status
     *            the HTTP status, or 0 when there was no answer
     * @param sentAtMillis
     *            wall clock when the request was sent
     * @param answeredAtMillis
     *            wall clock when the answer arrived (or the client gave up)
     */
    public record Turn(String conversationId, String input, String node, int status, long sentAtMillis, long answeredAtMillis) {
        public Outcome outcome() {
            return Outcome.ofStatus(status);
        }
    }

    /**
     * One request the mock LLM logged ({@code scripts/cluster-demo/mock_llm.py}).
     *
     * @param user
     *            the last user message of the prompt, i.e. the turn's input
     * @param previousUser
     *            the user message before it ({@code ""} for the first turn)
     * @param role
     *            the role of the prompt's last message; only {@code user} calls
     *            open a turn (tool rounds follow them)
     * @param startSeconds
     *            epoch seconds the request arrived
     * @param endSeconds
     *            epoch seconds the answer was sent
     */
    public record LlmCall(String user, String previousUser, String role, double startSeconds, double endSeconds) {
    }

    public enum Kind {
        LOST, DUPLICATED, PHANTOM, REFUSED_BUT_COMMITTED, OUT_OF_ORDER, OVERLAP, STALE_CONTEXT
    }

    public record Violation(Kind kind, String conversationId, String detail) {
        @Override
        public String toString() {
            return kind + " [" + conversationId + "] " + detail;
        }
    }

    /**
     * The verdict over a whole run.
     *
     * @param violations
     *            every breach found, in conversation order
     * @param committedTurns
     *            committed inputs across all conversations
     * @param outcomes
     *            client outcomes by kind
     * @param zombieOverlaps
     *            LLM calls of never-committed turns that overlapped another call of
     *            the same conversation (informational)
     */
    public record Report(List<Violation> violations, int committedTurns, Map<Outcome, Integer> outcomes, int zombieOverlaps) {

        public boolean clean() {
            return violations.isEmpty();
        }

        public List<Violation> of(Kind kind) {
            return violations.stream().filter(v -> v.kind() == kind).toList();
        }

        /** A one-paragraph summary for assertion messages and the result file. */
        public String summary() {
            Map<Kind, Long> byKind = violations.stream()
                    .collect(Collectors.groupingBy(Violation::kind, LinkedHashMap::new, Collectors.counting()));
            StringBuilder sb = new StringBuilder();
            sb.append(committedTurns).append(" committed turns; client outcomes ").append(outcomes)
                    .append("; zombie overlaps ").append(zombieOverlaps).append("; violations ")
                    .append(byKind.isEmpty() ? "none" : byKind.toString());
            violations.stream().limit(20).forEach(v -> sb.append("\n  ").append(v));
            if (violations.size() > 20) {
                sb.append("\n  ... ").append(violations.size() - 20).append(" more");
            }
            return sb.toString();
        }
    }

    /**
     * Overlaps shorter than this are clock noise between log writes, not two turns.
     */
    static final double OVERLAP_TOLERANCE_SECONDS = 0.010;

    private ConversationHistoryChecker() {
    }

    /**
     * @param turns
     *            every request the clients sent, in any order
     * @param committed
     *            conversationId -> committed user inputs in step order; every
     *            conversation the run touched must be present (an absent one is
     *            read as empty)
     * @param llmCalls
     *            the mock LLM's log; calls whose user message no client sent are
     *            ignored (other scenarios share the mock)
     */
    public static Report check(Collection<Turn> turns, Map<String, List<String>> committed, Collection<LlmCall> llmCalls) {
        List<Violation> violations = new ArrayList<>();
        Map<String, Turn> byInput = new HashMap<>();
        Map<String, List<Turn>> byConversation = new LinkedHashMap<>();
        Map<Outcome, Integer> outcomes = new LinkedHashMap<>();
        for (Turn t : turns) {
            Turn previous = byInput.put(t.input(), t);
            if (previous != null) {
                throw new IllegalArgumentException("the suite must send every input once, but sent twice: " + t.input());
            }
            byConversation.computeIfAbsent(t.conversationId(), k -> new ArrayList<>()).add(t);
            outcomes.merge(t.outcome(), 1, Integer::sum);
        }

        Map<String, List<LlmCall>> callsByInput = new HashMap<>();
        for (LlmCall c : llmCalls) {
            if ("user".equals(c.role()) && byInput.containsKey(c.user())) {
                callsByInput.computeIfAbsent(c.user(), k -> new ArrayList<>()).add(c);
            }
        }

        int committedTurns = 0;
        int zombieOverlaps = 0;
        Set<String> conversations = new HashSet<>(byConversation.keySet());
        conversations.addAll(committed.keySet());
        for (String conversationId : conversations.stream().sorted().toList()) {
            List<String> history = committed.getOrDefault(conversationId, List.of());
            List<Turn> sent = byConversation.getOrDefault(conversationId, List.of());
            committedTurns += history.size();

            Map<String, Integer> position = new HashMap<>();
            Map<String, Integer> count = new LinkedHashMap<>();
            for (int i = 0; i < history.size(); i++) {
                String input = history.get(i);
                count.merge(input, 1, Integer::sum);
                position.putIfAbsent(input, i);
            }
            count.forEach((input, n) -> {
                if (n > 1) {
                    violations.add(new Violation(Kind.DUPLICATED, conversationId, "'" + input + "' committed " + n + " times"));
                }
                Turn t = byInput.get(input);
                if (t == null || !t.conversationId().equals(conversationId)) {
                    violations.add(new Violation(Kind.PHANTOM, conversationId, "'" + input + "' committed but never sent here"));
                }
            });

            for (Turn t : sent) {
                boolean stored = position.containsKey(t.input());
                if (t.outcome() == Outcome.OK && !stored) {
                    violations.add(new Violation(Kind.LOST, conversationId,
                            "'" + t.input() + "' answered 200 by " + t.node() + " but not committed"));
                }
                if (t.outcome() == Outcome.REFUSED && stored) {
                    violations.add(new Violation(Kind.REFUSED_BUT_COMMITTED, conversationId,
                            "'" + t.input() + "' answered " + t.status() + " by " + t.node() + " but committed at step " + position.get(t.input())));
                }
            }

            // Real-time order: A answered before B was sent => A before B in the history.
            List<Turn> answeredFirst = sent.stream().filter(t -> t.outcome() == Outcome.OK && position.containsKey(t.input()))
                    .sorted(Comparator.comparingLong(Turn::answeredAtMillis)).toList();
            for (Turn b : sent) {
                Integer pb = position.get(b.input());
                if (pb == null) {
                    continue;
                }
                for (Turn a : answeredFirst) {
                    if (a.answeredAtMillis() >= b.sentAtMillis()) {
                        break;
                    }
                    if (a != b && position.get(a.input()) > pb) {
                        violations.add(new Violation(Kind.OUT_OF_ORDER, conversationId, "'" + a.input() + "' was answered before '" + b.input()
                                + "' was sent, but is stored after it (" + position.get(a.input()) + " > " + pb + ")"));
                    }
                }
            }

            // What the LLM saw: committed turns never overlap and each saw its predecessor.
            List<LlmCall> committedCalls = new ArrayList<>();
            List<LlmCall> allCalls = new ArrayList<>();
            for (Turn t : sent) {
                List<LlmCall> calls = callsByInput.getOrDefault(t.input(), List.of());
                allCalls.addAll(calls);
                if (position.containsKey(t.input()) && !calls.isEmpty()) {
                    // The call that produced the committed reply is the last one started.
                    committedCalls.add(calls.stream().max(Comparator.comparingDouble(LlmCall::startSeconds)).orElseThrow());
                }
            }
            committedCalls.sort(Comparator.comparingDouble(LlmCall::startSeconds));
            for (int i = 1; i < committedCalls.size(); i++) {
                LlmCall a = committedCalls.get(i - 1);
                LlmCall b = committedCalls.get(i);
                if (b.startSeconds() < a.endSeconds() - OVERLAP_TOLERANCE_SECONDS) {
                    violations.add(new Violation(Kind.OVERLAP, conversationId, "'" + b.user() + "' started "
                            + Math.round((a.endSeconds() - b.startSeconds()) * 1000) + " ms before '" + a.user() + "' ended"));
                }
            }
            allCalls.sort(Comparator.comparingDouble(LlmCall::startSeconds));
            Set<LlmCall> committedSet = new HashSet<>(committedCalls);
            for (int i = 1; i < allCalls.size(); i++) {
                LlmCall a = allCalls.get(i - 1);
                LlmCall b = allCalls.get(i);
                boolean overlaps = b.startSeconds() < a.endSeconds() - OVERLAP_TOLERANCE_SECONDS;
                if (overlaps && !(committedSet.contains(a) && committedSet.contains(b))) {
                    zombieOverlaps++;
                }
            }
            Map<String, LlmCall> committedCallByInput = new HashMap<>();
            committedCalls.forEach(c -> committedCallByInput.put(c.user(), c));
            for (int i = 1; i < history.size(); i++) {
                LlmCall call = committedCallByInput.get(history.get(i));
                if (call == null) {
                    continue; // not an LLM turn of this run, or the mock log was not read
                }
                String expected = history.get(i - 1);
                if (!expected.equals(call.previousUser()) && !truncatedMatch(expected, call.previousUser())) {
                    violations.add(new Violation(Kind.STALE_CONTEXT, conversationId, "'" + history.get(i) + "' (step " + i
                            + ") ran on top of '" + call.previousUser() + "' instead of the committed '" + expected + "'"));
                }
            }
        }
        return new Report(List.copyOf(violations), committedTurns, outcomes, zombieOverlaps);
    }

    /** The mock truncates logged messages to 80 characters. */
    private static boolean truncatedMatch(String full, String logged) {
        return logged != null && logged.length() == 80 && full.startsWith(logged);
    }
}
