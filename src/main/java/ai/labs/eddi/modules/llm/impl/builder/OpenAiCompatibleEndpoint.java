/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleProvider.Capabilities;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The generic {@code openai-compatible} LLM type: one self-hosted (or otherwise
 * unlisted) server that speaks the OpenAI chat-completions protocol — vLLM,
 * llama.cpp's {@code llama-server}, TGI, KServe, LiteLLM, LocalAI, ...
 * <p>
 * Unlike the named providers of {@link OpenAiCompatibleProviders}, such an
 * endpoint has no fixed URL and no fixed model, and EDDI cannot know what it
 * supports. So the agent designer declares it, per endpoint, in the task's (or
 * cascade step's) own parameters:
 * <ul>
 * <li>{@code baseUrl}, {@code modelName} — required; {@code http://} is fine
 * (in-cluster traffic), cloud-metadata targets are refused;</li>
 * <li>{@code apiKey} — optional; when blank, no {@code Authorization} header is
 * sent at all;</li>
 * <li>{@code jsonMode}, {@code jsonModeWithTools}, {@code vision} — capability
 * flags, {@code "true"}/{@code "false"}, <b>off by default</b>: an unknown
 * server is never sent a request feature it may answer with a 400. Expressed in
 * the same vocabulary as the catalog's {@link Capabilities};</li>
 * <li>{@code serverName} — free text reported as {@code gen_ai.provider.name}
 * (default {@value #TYPE}), so traces tell a {@code vllm} from a
 * {@code llama-server}.</li>
 * </ul>
 * A literal flag that is neither {@code true} nor {@code false} is a deploy
 * error, reported by name, never read as a silent {@code false}. A templated
 * flag is judged per request once resolved; one that still is not a boolean
 * then counts as {@code false} with a WARN, and never fails the turn.
 */
public final class OpenAiCompatibleEndpoint {

    /** The LLM task {@code type}. */
    public static final String TYPE = "openai-compatible";

    public static final String KEY_BASE_URL = "baseUrl";
    public static final String KEY_MODEL_NAME = "modelName";
    public static final String KEY_SERVER_NAME = "serverName";
    public static final String KEY_JSON_MODE = "jsonMode";
    public static final String KEY_JSON_MODE_WITH_TOOLS = "jsonModeWithTools";
    public static final String KEY_VISION = "vision";

    /**
     * The capability flags: they shape requests, not the model instance, so they
     * are kept out of the model cache key.
     */
    public static final Set<String> CAPABILITY_KEYS = Set.of(KEY_JSON_MODE, KEY_JSON_MODE_WITH_TOOLS, KEY_VISION);

    /** Every key this type reads on top of the OpenAI builder's own. */
    public static final Set<String> OWN_KEYS = Set.of(KEY_SERVER_NAME, KEY_JSON_MODE, KEY_JSON_MODE_WITH_TOOLS, KEY_VISION);

    /**
     * Longest {@code serverName} used as a telemetry label; it becomes a meter tag,
     * so it is bounded.
     */
    static final int MAX_SERVER_NAME_LENGTH = 32;
    private static final Pattern SERVER_NAME = Pattern.compile("[a-z0-9._-]{1," + MAX_SERVER_NAME_LENGTH + "}");

    private static final Logger LOGGER = Logger.getLogger(OpenAiCompatibleEndpoint.class);
    private static final int MAX_WARNED = 256;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private OpenAiCompatibleEndpoint() {
        // non-instantiable utility
    }

    /** Whether {@code type} is this generic type; case- and space-insensitive. */
    public static boolean isType(String type) {
        return type != null && TYPE.equals(type.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The capabilities an {@code openai-compatible} endpoint declares, or empty for
     * any other type.
     * <p>
     * Read per request, so it never throws: a flag that is still not a boolean once
     * resolved (a template rendering to something else, an unresolvable reference)
     * counts as {@code false}, the conservative answer, with one WARN per distinct
     * value. Literal values were already checked at deploy by {@link #problems}.
     */
    public static Optional<Capabilities> capabilities(String type, Map<String, String> parameters) {
        if (!isType(type)) {
            return Optional.empty();
        }
        return Optional.of(new Capabilities(flag(parameters, KEY_JSON_MODE), flag(parameters, KEY_JSON_MODE_WITH_TOOLS), List.of()));
    }

    /**
     * Whether an {@code openai-compatible} endpoint declares {@code vision=true};
     * {@code false} for every other type. Never throws, as {@link #capabilities}.
     */
    public static boolean declaresVision(String type, Map<String, String> parameters) {
        return isType(type) && flag(parameters, KEY_VISION);
    }

    /**
     * The label telemetry reports as {@code gen_ai.provider.name}: the endpoint's
     * {@code serverName}, lower-cased, if it is 1-{@value #MAX_SERVER_NAME_LENGTH}
     * characters of {@code [a-z0-9._-]}; otherwise {@value #TYPE}. Bounded on
     * purpose — it is a meter tag, and a free-text tag is a cardinality leak.
     */
    public static String telemetryName(Map<String, String> parameters) {
        String raw = parameters == null ? null : parameters.get(KEY_SERVER_NAME);
        if (raw == null || raw.isBlank()) {
            return TYPE;
        }
        String candidate = raw.trim().toLowerCase(Locale.ROOT);
        return SERVER_NAME.matcher(candidate).matches() ? candidate : TYPE;
    }

    /**
     * What is wrong with an endpoint's configuration, one message per problem;
     * empty when it is usable. The deploy-time check: a missing {@code baseUrl} or
     * {@code modelName}, and a <em>literal</em> capability flag that is not a
     * boolean. A value still holding a template or a reference ({@code {...}},
     * {@code ${vars:...}}) is not judged: it is only known per request, where
     * {@link #capabilities} degrades instead of failing.
     */
    public static List<String> problems(Map<String, String> parameters) {
        List<String> problems = missingRequired(parameters);
        Map<String, String> p = parameters == null ? Map.of() : parameters;
        for (String key : List.of(KEY_JSON_MODE, KEY_JSON_MODE_WITH_TOOLS, KEY_VISION)) {
            String raw = p.get(key);
            if (raw != null && !raw.isBlank() && !isDeferred(raw) && parseFlag(raw) == null) {
                problems.add(invalidFlagMessage(key, raw));
            }
        }
        return problems;
    }

    /**
     * The required fields that are missing: the only thing that stops a model from
     * being built. Capability flags never do.
     */
    public static List<String> missingRequired(Map<String, String> parameters) {
        List<String> problems = new ArrayList<>();
        Map<String, String> p = parameters == null ? Map.of() : parameters;
        requirePresent(p, KEY_BASE_URL, "the URL of the server's OpenAI API, e.g. http://vllm.inference.svc:8000/v1", problems);
        requirePresent(p, KEY_MODEL_NAME, "the model id the server serves, e.g. ibm-granite/granite-4.0-h-small", problems);
        return problems;
    }

    /**
     * {@code parameters} with the capability flags passed through {@code resolver},
     * for an {@code openai-compatible} type only; any other type gets the map back
     * unchanged.
     * <p>
     * Task parameters reach the request-building code after the template engine
     * ({@code {...}} is rendered) but before {@code ChatModelRegistry} resolves
     * global-variable and vault references, so a flag written as
     * {@code ${vars:vllm-json}} would otherwise be read unresolved. A resolver that
     * throws leaves the value as it was, which {@link #capabilities} then reads as
     * {@code false}.
     */
    public static Map<String, String> withResolvedFlags(String type, Map<String, String> parameters, UnaryOperator<String> resolver) {
        if (!isType(type) || parameters == null || resolver == null) {
            return parameters;
        }
        Map<String, String> resolved = null;
        for (String key : CAPABILITY_KEYS) {
            String raw = parameters.get(key);
            if (raw == null || !raw.contains("${")) {
                continue;
            }
            String value;
            try {
                value = resolver.apply(raw);
            } catch (RuntimeException e) {
                value = raw;
            }
            if (resolved == null) {
                resolved = new HashMap<>(parameters);
            }
            resolved.put(key, value);
        }
        return resolved == null ? parameters : resolved;
    }

    private static void requirePresent(Map<String, String> p, String key, String hint, List<String> problems) {
        String value = p.get(key);
        if (value == null || value.isBlank()) {
            problems.add("LLM type '" + TYPE + "' requires '" + key + "' — " + hint);
        }
    }

    /** A value only resolved at run time (template or reference). */
    static boolean isDeferred(String value) {
        return value.contains("{");
    }

    static boolean flag(Map<String, String> parameters, String key) {
        String raw = parameters == null ? null : parameters.get(key);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        Boolean parsed = parseFlag(raw);
        if (parsed == null) {
            warnOnce(key, raw);
            return false;
        }
        return parsed;
    }

    /**
     * One WARN per distinct (key, value): the flag is read on every request, so an
     * unconditional warning would repeat every turn. Bounded, so a templated value
     * fed from user input cannot grow the set without limit.
     */
    private static void warnOnce(String key, String raw) {
        String marker = key + "=" + raw;
        if (WARNED.size() < MAX_WARNED && WARNED.add(marker)) {
            LOGGER.warnf("%s; treated as \"false\" for this request", sanitize(invalidFlagMessage(key, raw)));
        }
    }

    private static Boolean parseFlag(String raw) {
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String invalidFlagMessage(String key, String raw) {
        String shown = raw.length() > 40 ? raw.substring(0, 40) + "..." : raw;
        return "LLM type '" + TYPE + "': parameter '" + key + "' must be \"true\" or \"false\", not \"" + shown.replaceAll("[\\r\\n]", " ")
                + "\"";
    }
}
