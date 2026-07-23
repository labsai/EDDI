/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.capability;

import ai.labs.eddi.modules.llm.capability.ModelCapabilityService.Support;
import dev.langchain4j.model.chat.request.ResponseFormat;

import java.util.Locale;
import java.util.Set;

import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_AZURE_OPENAI;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_GEMINI;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_GEMINI_VERTEX;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_MISTRAL;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_OPENAI;

/**
 * Decides whether one outgoing
 * {@link dev.langchain4j.model.chat.request.ChatRequest} may carry a schemaless
 * JSON response format ({@code ResponseFormat.JSON}).
 * <p>
 * JSON mode is applied <strong>per request</strong>, never baked into the model
 * instance. Model instances are cached and reused across turns and across
 * execution modes, so a builder-level {@code responseFormat} travels into
 * requests that must not carry it — that is exactly what produced the Gemini
 * {@code "Function calling with a response mime type: 'application/json' is
 * unsupported"} 400 (see {@code docs/changelog.md}, 2026-04-02). Keeping the
 * decision on the request keeps it correct for every mode the same cached model
 * is used in.
 * <p>
 * The built-in matrix is derived from what the langchain4j 1.18.0 provider
 * bindings actually do with a schemaless {@code ResponseFormat.JSON} taken from
 * {@code ChatRequest#responseFormat()}:
 * <table border="1">
 * <caption>Request-level schemaless JSON support</caption>
 * <tr>
 * <th>provider</th>
 * <th>without tools</th>
 * <th>with tools</th>
 * <th>evidence</th>
 * </tr>
 * <tr>
 * <td>openai</td>
 * <td>yes</td>
 * <td>yes</td>
 * <td>{@code OpenAiUtils#toOpenAiResponseFormat} → {@code json_object}</td>
 * </tr>
 * <tr>
 * <td>azure-openai</td>
 * <td>yes</td>
 * <td>yes</td>
 * <td>{@code InternalAzureOpenAiHelper#toAzureOpenAiResponseFormat} →
 * {@code ChatCompletionsJsonResponseFormat}</td>
 * </tr>
 * <tr>
 * <td>mistral</td>
 * <td>yes</td>
 * <td>yes</td>
 * <td>{@code MistralAiMapper#toMistralAiResponseFormat} →
 * {@code JSON_OBJECT}</td>
 * </tr>
 * <tr>
 * <td>gemini, gemini-vertex</td>
 * <td>yes</td>
 * <td><strong>no</strong></td>
 * <td>maps to {@code responseMimeType=application/json}, which the Gemini API
 * rejects when {@code tools} are also present</td>
 * </tr>
 * <tr>
 * <td>anthropic, bedrock</td>
 * <td>no</td>
 * <td>no</td>
 * <td>both throw {@code UnsupportedFeatureException} for JSON <em>without</em>
 * a schema</td>
 * </tr>
 * <tr>
 * <td>ollama, jlama, huggingface, oracle-genai</td>
 * <td>no</td>
 * <td>no</td>
 * <td>not verified against the provider API — opt in per task with
 * {@code jsonResponseFormat: "on"}</td>
 * </tr>
 * </table>
 * <p>
 * The matrix is only the {@code auto} default. An agent designer overrides it
 * per task via {@code jsonResponseFormat} ({@code on} / {@code off} /
 * {@code auto}) — {@code on} is the escape hatch for a provider or gateway the
 * built-in table does not know about yet, and it bypasses the with-tools guard
 * as well.
 *
 * @param requested
 *            whether the task asked for structured output at all (i.e.
 *            {@code convertToObject=true})
 * @param provider
 *            the resolved LLM provider type (e.g. {@code openai}); for a
 *            cascade step this is the <em>step's</em> provider, not the task
 *            default
 * @param override
 *            the per-task override; {@link Support#AUTO} defers to the matrix
 *
 * @since 6.1.0
 */
public record JsonResponseFormatPolicy(boolean requested, String provider, Support override) {

    /** Never applies — the request is sent exactly as it is built. */
    public static final JsonResponseFormatPolicy DISABLED = new JsonResponseFormatPolicy(false, null, Support.AUTO);

    /** Providers whose binding maps a schemaless request-level JSON format. */
    private static final Set<String> REQUEST_LEVEL_JSON = Set.of(
            LLM_TYPE_OPENAI, LLM_TYPE_AZURE_OPENAI, LLM_TYPE_MISTRAL, LLM_TYPE_GEMINI, LLM_TYPE_GEMINI_VERTEX);

    /** Subset of {@link #REQUEST_LEVEL_JSON} that tolerates it alongside tools. */
    private static final Set<String> REQUEST_LEVEL_JSON_WITH_TOOLS = Set.of(
            LLM_TYPE_OPENAI, LLM_TYPE_AZURE_OPENAI, LLM_TYPE_MISTRAL);

    public JsonResponseFormatPolicy {
        override = override == null ? Support.AUTO : override;
    }

    /**
     * @param requested
     *            {@code convertToObject} for this task
     * @param provider
     *            the resolved provider type of the model the request goes to
     * @param overrideToken
     *            the raw {@code jsonResponseFormat} task setting ({@code on} /
     *            {@code off} / {@code auto} / null)
     */
    public static JsonResponseFormatPolicy of(boolean requested, String provider, String overrideToken) {
        return new JsonResponseFormatPolicy(requested, provider, Support.parse(overrideToken));
    }

    /**
     * Whether the provider accepts a schemaless request-level JSON format at all.
     */
    public static boolean supportsRequestLevelJson(String provider) {
        return REQUEST_LEVEL_JSON.contains(normalize(provider));
    }

    /**
     * Whether the provider accepts a schemaless request-level JSON format on a
     * request that <em>also</em> carries tool specifications.
     */
    public static boolean supportsRequestLevelJsonWithTools(String provider) {
        return REQUEST_LEVEL_JSON_WITH_TOOLS.contains(normalize(provider));
    }

    /**
     * @param toolsInRequest
     *            whether the request being built carries tool specifications
     * @return {@code true} if this request may carry {@code ResponseFormat.JSON}
     */
    public boolean applies(boolean toolsInRequest) {
        if (!requested || override == Support.OFF) {
            return false;
        }
        if (override == Support.ON) {
            return true;
        }
        return toolsInRequest ? supportsRequestLevelJsonWithTools(provider) : supportsRequestLevelJson(provider);
    }

    /**
     * The response format to set on the request, or {@code null} to leave the
     * request untouched.
     *
     * @param toolsInRequest
     *            whether the request being built carries tool specifications
     */
    public ResponseFormat resolve(boolean toolsInRequest) {
        return applies(toolsInRequest) ? ResponseFormat.JSON : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
