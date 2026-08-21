/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import ai.labs.eddi.modules.llm.governance.RemoteTextGovernor;
import ai.labs.eddi.modules.llm.governance.ToolResultProvenance;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;

/**
 * The one place a tool result is inspected before it becomes model context.
 * <p>
 * Tool descriptions have been governed for a while; their <em>results</em> were
 * appended verbatim. So the channel that actually carries third-party bulk text
 * — an HTTP API's JSON body, an MCP server's resource content, a remote agent's
 * answer — was the ungoverned one, and it is by far the larger surface.
 * <h3>It never throws</h3> A guardrail that throws would put
 * attacker-influenced text into an exception message on a path that classifies
 * exceptions for retry, where a "blocked" verdict would be indistinguishable
 * from a transient provider error and would be retried. A terminal verdict is
 * therefore a returned {@link Outcome}, and any internal failure degrades to
 * {@link #ACTION_ALLOW}: a guardrail bug must not take a conversation down.
 * <h3>What it does not do</h3> This is not a content classifier and does not
 * call a model. It applies the same directive-shape rule the tool-description
 * path already uses, so the two cannot diverge, and leaves semantic judgement
 * to whatever policy layer an operator puts in front of EDDI.
 */
@ApplicationScoped
public class ToolResultGuardrail {

    private static final Logger LOGGER = Logger.getLogger(ToolResultGuardrail.class);

    /** Nothing matched; the result passes through unchanged. */
    public static final String ACTION_ALLOW = "allow";

    /** Replaces a blocked result. Deliberately neutral and quotes nothing. */
    static final String BLOCKED_NOTICE = "[tool result withheld: it contained instruction-shaped content and this agent is configured to "
            + "block such results. The call itself completed; only its output was withheld.]";

    /** Result of inspecting one tool result. */
    public record Outcome(String action, String result) {
    }

    private final MeterRegistry meterRegistry;

    @Inject
    public ToolResultGuardrail(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Inspects one tool result and returns what the model should see.
     *
     * @param toolName
     *            dispatch name the model called
     * @param source
     *            provenance tag from {@code ToolSourceRegistry}
     * @param result
     *            the tool's output, already truncated
     * @param config
     *            the task's configuration; {@code null} means defaults
     */
    public Outcome inspect(String toolName, String source, String result, ToolResultGuardrailConfig config) {
        try {
            return inspectOrThrow(toolName, source, result, config);
        } catch (RuntimeException e) {
            // Degrade to allow, loudly. The alternative — failing the tool call —
            // turns a guardrail defect into an outage, and this runs on every tool
            // result of every turn.
            LOGGER.errorf(e, "Tool-result guardrail failed for tool '%s'; passing the result through ungoverned", sanitize(toolName));
            count("error", source);
            return new Outcome(ACTION_ALLOW, result);
        }
    }

    private Outcome inspectOrThrow(String toolName, String source, String result, ToolResultGuardrailConfig config) {
        if (result == null) {
            return new Outcome(ACTION_ALLOW, null);
        }

        ToolResultGuardrailConfig effective = config != null ? config : new ToolResultGuardrailConfig();
        if (!isTrue(effective.getEnabled(), true) || !appliesTo(effective, source)) {
            return new Outcome(ACTION_ALLOW, result);
        }

        String action = ACTION_ALLOW;
        String governed = result;

        if (!isExempt(effective, toolName) && RemoteTextGovernor.containsDirective(result)) {
            action = normalizeAction(effective.getDirectiveAction());
            LOGGER.warnf("Tool '%s' (source '%s') returned directive-shaped content — action '%s'", sanitize(toolName), sanitize(source), action);
            governed = switch (action) {
                case ToolResultGuardrailConfig.ACTION_BLOCK -> BLOCKED_NOTICE;
                // Bounded by Integer.MAX_VALUE, not by a cap: truncation is
                // ToolResponseTruncator's job and has already happened, and
                // re-truncating here would silently shorten a result the operator
                // configured to that length.
                case ToolResultGuardrailConfig.ACTION_REDACT -> RemoteTextGovernor.govern(result, Integer.MAX_VALUE);
                default -> result;
            };
        }

        count(action, source);

        if (isTrue(effective.getMarkProvenance(), true)) {
            governed = ToolResultProvenance.mark(toolName, source, governed);
        }
        return new Outcome(action, governed);
    }

    /**
     * Unknown actions fall back to {@code warn}, the least destructive of the
     * three. A typo in a config field must not silently become "block every tool
     * result", and must not become "do nothing" either — a warn leaves a trail.
     */
    private static String normalizeAction(String configured) {
        if (configured == null) {
            return ToolResultGuardrailConfig.ACTION_REDACT;
        }
        return switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case ToolResultGuardrailConfig.ACTION_BLOCK -> ToolResultGuardrailConfig.ACTION_BLOCK;
            case ToolResultGuardrailConfig.ACTION_REDACT -> ToolResultGuardrailConfig.ACTION_REDACT;
            default -> ToolResultGuardrailConfig.ACTION_WARN;
        };
    }

    private static boolean appliesTo(ToolResultGuardrailConfig config, String source) {
        List<String> sources = config.getAppliesToSources();
        if (sources == null || sources.isEmpty()) {
            return true;
        }
        return sources.stream().anyMatch(configured -> configured != null && configured.equalsIgnoreCase(source));
    }

    private static boolean isExempt(ToolResultGuardrailConfig config, String toolName) {
        List<String> exempt = config.getExemptTools();
        if (exempt == null || exempt.isEmpty() || toolName == null) {
            return false;
        }
        return exempt.stream().anyMatch(toolName::equalsIgnoreCase);
    }

    private static boolean isTrue(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }

    /**
     * Counts by action and source only. Both are bounded categoricals; the tool
     * name is author-supplied and unbounded, so tagging with it would let a config
     * author mint Micrometer series without limit.
     */
    private void count(String action, String source) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("guardrail.toolresult.count", "action", action, "source", source == null ? "unknown" : source).increment();
    }

    /** Strips CR/LF so a remote-supplied name cannot forge extra log lines. */
    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[\\r\\n]", "_");
    }
}
