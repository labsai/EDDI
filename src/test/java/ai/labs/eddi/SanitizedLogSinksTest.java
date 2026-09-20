/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the {@code LogSanitizer.sanitize(…)} calls that closed CodeQL's
 * {@code java/log-injection} alerts, so removing one fails the build rather
 * than re-opening an alert nobody looks at until the next scan.
 *
 * <p>
 * The thirty-eight statements below are not a sample: they are exactly the
 * sinks CodeQL reported across these eight files, each identified by a
 * distinctive fragment of its own message rather than by a line number, which
 * moves whenever anything above it does. For each, every argument that was
 * flagged must appear only inside a {@code sanitize(…)} call.
 * </p>
 *
 * <h3>Why a source guard rather than thirty-eight behavioural tests</h3>
 * <p>
 * Most of these lines sit deep inside a phase loop or an exception path that
 * takes a full group conversation to reach, and a test that builds one to
 * observe a single WARN tests the harness more than the fix. The behavioural
 * half of this change is covered where it is cheap to reach — see
 * {@code GroupHitlCoordinatorLogInjectionTest} — and the CWE-117 property
 * itself, for the throwable that no call site can reach, is covered by
 * {@code LogRecordBoundaryForgeryTest}.
 * </p>
 *
 * <h3>If this test fails</h3>
 * <p>
 * Either a {@code sanitize(…)} was dropped — put it back — or the message was
 * reworded, in which case update the fragment here. Do not delete the entry:
 * the alert it closed is still open upstream.
 * </p>
 *
 * @see ai.labs.eddi.utils.LogSanitizer
 */
@DisplayName("sanitized log sinks (CWE-117)")
class SanitizedLogSinksTest {

    /**
     * One flagged log statement: a fragment of its message, and the argument
     * expressions that must stay wrapped.
     */
    private record Sink(String messageFragment, List<String> expressions) {
    }

    private record FileSinks(String path, List<Sink> sinks) {
    }

    private static Sink sink(String messageFragment, String... expressions) {
        return new Sink(messageFragment, Arrays.asList(expressions));
    }

    private static FileSinks sinksOf(String path, Sink... sinks) {
        return new FileSinks(path, Arrays.asList(sinks));
    }

    private static final List<FileSinks> FLAGGED = List.of(
            sinksOf("src/main/java/ai/labs/eddi/engine/internal/groups/GroupHitlCoordinator.java",
                    sink("Group discussion %s was moved to %s elsewhere — stopping this leg at the phase boundary", "gc.getId()"),
                    sink("Phase-boundary persisted-state re-check failed for %s: %s (continuing)", "e.getMessage()", "gc.getId()"),
                    sink("No-progress TASK pause detected for GC %s at phase %d — failing to guarantee termination", "gc.getId()"),
                    sink("Pause→cancel conversion for GC %s lost a state race — leaving persisted state", "gc.getId()"),
                    sink("Pause→cancel conversion for GC %s skipped — conversation was deleted", "gc.getId()"),
                    sink("Failed to convert just-committed pause of GC %s to CANCELLED: %s", "e.getMessage()", "gc.getId()"),
                    sink("Cancel signal landed while pausing GC %s — converted pause to CANCELLED", "gc.getId()"),
                    sink("Cancel listener threw for GC %s after CANCELLED was committed — ignoring: %s", "e.getMessage()", "gc.getId()"),
                    sink("Scheduled group HITL timeout for %s at %s (policy: %s)", "gc.getId()"),
                    sink("Failed to schedule group HITL timeout for %s: %s", "e.getMessage()", "gc.getId()"),
                    sink("Task '%s' rejected with RETRY policy — reset to ASSIGNED", "entry.getKey()"),
                    sink("Failed to submit HITL cancellation audit entry for group conversation %s: %s", "e.getMessage()", "gc.getId()"),
                    sink("Terminal cleanup: group config %s not found — ephemeral agents of GC %s not cleaned", "gc.getGroupId()",
                            "gc.getId()"),
                    sink("Terminal cleanup failed for group conversation %s: %s", "e.getMessage()", "gc.getId()"),
                    sink("Cleaned up %d group HITL timeout schedule(s) for %s", "groupConversationId"),
                    sink("Failed to delete group HITL timeout schedule for %s: %s", "e.getMessage()", "groupConversationId")),

            sinksOf("src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java",
                    sink("Group discussion %s cancelled via control token at phase %d", "gc.getId()"),
                    sink("Max turns (%d) exceeded for group %s — skipping remaining phases", "gc.getGroupId()"),
                    sink("Cost ceiling reached for group %s at phase %d (spend $%s) — policy %s", "gc.getGroupId()"),
                    sink("Facilitator escalation for group %s suppressed at phase %d — the", "gc.getGroupId()"),
                    sink("Phase '%s' of group %s ended early after repeat %d: %s", "gc.getGroupId()", "outcome.reason()", "phase.name()"),
                    sink("Group discussion %s cancelled before HITL gate at phase %d", "gc.getId()"),
                    sink("(aborted wave) — pausing for human review instead of skipping them", "gc.getId()"),
                    sink("Group discussion %s ending early after phase %d on an END_DISCUSSION signal", "gc.getId()"),
                    sink("Group %s hit its cost ceiling with no remaining SYNTHESIS phase — completing without an answer", "gc.getGroupId()"),
                    sink("Group discussion %s was terminated elsewhere (expected %s) — not overwriting with COMPLETED", "gc.getId()"),
                    sink("Group discussion %s was deleted while running — discarding its result", "gc.getId()")),

            sinksOf("src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java",
                    sink("auto-rejecting the gated tool call(s) (system:group) and resuming for a tool-less answer", "gc.getId()",
                            "member.agentId()"),
                    sink("member-level HITL is unsupported inside a group; skipping the turn and cancelling the pause", "gc.getId()",
                            "member.agentId()"),
                    sink("Executing sub-group '%s' (depth %d) as member of parent group '%s'", "gc.getGroupId()", "subGroupId")),

            sinksOf("src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java",
                    sink("a finite policy resumes after the next restart (crash recovery) or a manual decision", "conversationId"),
                    sink("Resume of conversation %s failed — pause restored (AWAITING_HUMAN)", "conversationId"),
                    sink("Failed to restore pause after failed resume: %s", "conversationId")),

            sinksOf("src/main/java/ai/labs/eddi/engine/internal/groups/PhaseExecutionEngine.java",
                    sink("Group %s recorded a debate verdict at phase '%s': %s", "decision.outcome()", "gc.getId()", "phase.name()"),
                    sink("Group %s produced a debate judgment that could not be read as a verdict at phase '%s' —", "gc.getId()",
                            "phase.name()")),

            sinksOf("src/main/java/ai/labs/eddi/engine/audit/AuditLedgerService.java",
                    sink("Agent signing skipped for agent '{0}': {1}", "e.getMessage()", "entry.agentId()")),

            sinksOf("src/main/java/ai/labs/eddi/configs/groups/mongo/AgentGroupStore.java",
                    sink("Group '%s' phase '%s' is restricted to MODERATOR but the group names no moderatorAgentId —",
                            "groupConfiguration.getName()", "name")),

            sinksOf("src/main/java/ai/labs/eddi/integrations/slack/SlackGroupDiscussionListener.java",
                    sink("Failed to post group HITL approval notification for %s: %s", "e.getMessage()", "groupConversationId")));

    /**
     * A Java string literal, so a fragment inside a message never counts as code.
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final Pattern LOG_CALL = Pattern.compile("\\b(?:LOGGER|log)\\.(?:trace|debug|info|warn|error|fatal)[a-z]*\\(");

    @Test
    @DisplayName("every log sink CodeQL flagged still sanitizes the arguments it flagged")
    void everyFlaggedSinkStillSanitizes() {
        var failures = new ArrayList<String>();
        int checked = 0;

        for (FileSinks file : FLAGGED) {
            Path path = Path.of(System.getProperty("basedir", ".")).resolve(file.path());
            assertTrue(Files.isRegularFile(path), "Expected a source file at " + path);
            String source = read(path);

            for (Sink sink : file.sinks()) {
                checked++;
                String statement = statementContaining(source, sink.messageFragment(), file.path(), failures);
                if (statement == null) {
                    continue;
                }
                // Arguments only: a fragment of the message must never be read as
                // one of the expressions being checked.
                String code = STRING_LITERAL.matcher(statement).replaceAll("\"\"");
                for (String expression : sink.expressions()) {
                    int occurrences = count(code, standalone(expression));
                    int wrapped = count(code, Pattern.compile("sanitize\\(\\s*" + Pattern.quote(expression) + "\\s*\\)"));
                    if (occurrences != wrapped) {
                        failures.add(file.path() + ": the log call for \"" + abbreviate(sink.messageFragment()) + "\" passes "
                                + expression + " unsanitized (" + (occurrences - wrapped) + " of " + occurrences
                                + " occurrences). CodeQL reported this exact sink as java/log-injection; wrap it in"
                                + " LogSanitizer.sanitize(…) again. Statement: " + abbreviate(oneLine(statement)));
                    }
                }
            }
        }

        if (!failures.isEmpty()) {
            fail("CWE-117: " + failures.size() + " sanitized log sink(s) regressed:\n  - " + String.join("\n  - ", failures));
        }
        assertTrue(checked == 38, "expected to check all 38 flagged sinks, checked " + checked
                + " — an entry was added or removed without updating this count");
    }

    /**
     * The whole log statement carrying {@code fragment}: from the {@code LOGGER.}
     * that opens it to the {@code );} that ends its line. Registers a failure and
     * returns null when the fragment is missing or ambiguous, so one reworded
     * message does not hide the other thirty-seven.
     */
    private static String statementContaining(String source, String fragment, String path, List<String> failures) {
        int at = source.indexOf(fragment);
        if (at < 0) {
            failures.add(path + ": no log statement carries the message \"" + abbreviate(fragment)
                    + "\" any more. If it was reworded, update the fragment here — do not delete the entry,"
                    + " the CodeQL alert it closed is still open upstream.");
            return null;
        }
        if (source.indexOf(fragment, at + 1) >= 0) {
            failures.add(path + ": the message \"" + abbreviate(fragment)
                    + "\" occurs more than once, so this entry no longer identifies one statement — lengthen the fragment.");
            return null;
        }

        int start = -1;
        Matcher call = LOG_CALL.matcher(source);
        while (call.find() && call.start() < at) {
            start = call.start();
        }
        if (start < 0) {
            failures.add(path + ": \"" + abbreviate(fragment) + "\" is not inside a log call any more.");
            return null;
        }
        int end = source.indexOf(");\n", at);
        int endCr = source.indexOf(");\r\n", at);
        if (endCr >= 0 && (end < 0 || endCr < end)) {
            end = endCr;
        }
        if (end < 0) {
            failures.add(path + ": could not find the end of the log call for \"" + abbreviate(fragment) + "\".");
            return null;
        }
        return source.substring(start, end + 2);
    }

    /**
     * The expression where it stands on its own, so {@code name} does not also
     * match the {@code name} inside {@code phase.name()}.
     */
    private static Pattern standalone(String expression) {
        return Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(expression) + "(?![A-Za-z0-9_])");
    }

    private static int count(String haystack, Pattern pattern) {
        Matcher matcher = pattern.matcher(haystack);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String abbreviate(String text) {
        return text.length() <= 90 ? text : text.substring(0, 87) + "…";
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
