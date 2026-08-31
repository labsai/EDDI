/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.core.JsonParseException;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Discovers httpcall configurations from the agent's workflow and builds a
 * {@link ToolSpecification}/{@link ToolExecutor} pair for each {@link ApiCall}
 * (R2 step 2). Extracted from {@code AgentOrchestrator} as a pure move — no
 * behavior change; {@code AgentOrchestrator.discoverHttpCallTools} is kept as a
 * declared delegator (adapting {@link ToolContribution} back to the legacy
 * {@code HttpCallToolsResult} shape) since {@code buildToolSetup}'s merge flow
 * is not yet rewired to iterate {@link ToolSourceProvider}s — that is a
 * separate, later step.
 * <p>
 * Lives in {@code ai.labs.eddi.modules.llm.impl} — the same package as
 * {@code AgentOrchestrator} — rather than a new subpackage, deliberately unlike
 * Wave R's {@code groups} convention: this class depends on
 * {@link WorkflowTraversal}, a package-private shared utility also used by MCP
 * discovery and RAG. Moving to a new package would force widening a utility
 * several unrelated call sites share, for no benefit; staying in-package keeps
 * every dependency at its original visibility.
 */
class HttpCallToolsProvider implements ToolSourceProvider {

    private static final Logger LOGGER = Logger.getLogger(HttpCallToolsProvider.class);
    private static final String HTTPCALLS_TYPE = "eddi://ai.labs.httpcalls";

    /**
     * Cap for tool arguments echoed into a log line. Deliberately small: a log is
     * for identifying WHICH call failed to parse, not for reproducing its payload,
     * and an unbounded model-supplied string is a log-flooding vector on top of the
     * redaction concern below.
     */
    private static final int ARGS_LOG_MAX_BYTES = 512;

    /**
     * Keys produced by {@link IMemoryItemConverter#convert} that carry
     * authenticated identity and session state. LLM-provided tool arguments must
     * never override these — a prompt-injection attack could otherwise manipulate
     * userId/agentId in HTTP call templates.
     * <p>
     * This must stay in sync with every top-level namespace
     * {@code MemoryItemConverter#convert} writes. {@code snippets} and {@code vars}
     * were missing (flagged on PR #626): both are added by
     * {@code addSnippetsAndVars}, and {@code vars} in particular carries
     * deployment-wide configuration that httpcall templates read as
     * {@code {vars.<key>}} — leaving it unreserved let a prompt-injected tool
     * argument named {@code vars} shadow the whole namespace for that call.
     */
    private static final Set<String> RESERVED_TEMPLATE_KEYS = Set.of(
            "context", "properties", "memory",
            "snippets", "vars",
            "userInfo", "conversationInfo", "conversationLog");

    /**
     * A body template that is nothing but one Qute variable — the shape
     * {@code McpApiToolBuilder.buildBodyTemplate} always generates, where the model
     * writes the whole JSON document and that variable IS the request body.
     * <p>
     * Anchored, so a template that merely CONTAINS a variable does not match: in
     * {@code {"name":"{name}"}} the JSON around the value is EDDI's own and the
     * model never wrote it, so judging one substituted value as a JSON document
     * would refuse every correct call of that shape.
     *
     * @see #rejectUnparseableBody
     */
    private static final Pattern WHOLE_BODY_TEMPLATE = Pattern.compile("^\\{([A-Za-z_][A-Za-z0-9_]*)}$");

    /**
     * Strict parser for the model-written request body.
     * <p>
     * Its own mapper, not the injected {@link IJsonSerialization}, for one reason:
     * {@code FAIL_ON_TRAILING_TOKENS}. Jackson's default is to parse the first
     * complete value and ignore whatever follows, so a body with a trailing
     * sentence ("…} Sure, I created the agent!") or a closing markdown fence
     * validated clean here and then failed to bind at the API — leaving the model
     * with EDDI's positive assurance that its body was fine, which makes the next
     * attempt LESS likely to fix it. The shared mapper cannot be tightened: it is
     * also the persistence mapper, and {@code SerializationCustomizer} documents
     * why strictness there is not available.
     * <p>
     * The same posture six other classes take with LLM output — see
     * {@code ConvergenceDetector}, whose comment puts it as
     * "FAIL_ON_TRAILING_TOKENS is load-bearing, not hygiene".
     */
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final IResourceClientLibrary resourceClientLibrary;
    private final IApiCallExecutor apiCallExecutor;
    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;

    HttpCallToolsProvider(IAgentStore agentStore, IWorkflowStore workflowStore,
            IResourceClientLibrary resourceClientLibrary, IApiCallExecutor apiCallExecutor,
            IJsonSerialization jsonSerialization, IMemoryItemConverter memoryItemConverter) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.apiCallExecutor = apiCallExecutor;
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
    }

    @Override
    public String source() {
        return "http";
    }

    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        boolean enabled = ctx.task().getEnableHttpCallTools() == null || ctx.task().getEnableHttpCallTools();
        if (!enabled) {
            return ToolContribution.empty();
        }
        return discover(ctx.memory());
    }

    /**
     * Discovers httpcall configurations from the workflow and creates
     * ToolSpecification + ToolExecutor for each ApiCall.
     * <p>
     * Traverses: memory → agentId/version → AgentConfiguration → workflows →
     * WorkflowConfiguration → filter httpcall steps → load ApiCallsConfiguration →
     * create tools from each ApiCall.
     * <p>
     * {@code toolSources} is deliberately empty in the returned contribution —
     * source tagging happens at merge time in {@code AgentOrchestrator
     * .mergeExternalTools}, not at discovery time, exactly as before this move.
     */
    ToolContribution discover(IConversationMemory memory) {
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();
        Map<String, String> endpoints = new HashMap<>();
        Map<String, ToolRequestResolver> resolvers = new HashMap<>();

        try {
            LOGGER.infof("Discovering httpcall tools for agent: %s v%s", memory.getAgentId(), memory.getAgentVersion());

            var stepConfigs = WorkflowTraversal.discoverConfigs(memory, HTTPCALLS_TYPE, ApiCallsConfiguration.class, agentStore,
                    workflowStore, resourceClientLibrary);

            for (var stepConfig : stepConfigs) {
                ApiCallsConfiguration httpCallsConfig = stepConfig.config();
                String targetServerUrl = httpCallsConfig.getTargetServerUrl();

                for (ApiCall apiCall : httpCallsConfig.getHttpCalls()) {
                    if (apiCall.getName() == null || apiCall.getName().isBlank()) {
                        continue;
                    }

                    ToolSpecification.Builder specBuilder = ToolSpecification.builder().name(apiCall.getName())
                            .description(apiCall.getDescription() != null ? apiCall.getDescription() : "Execute " + apiCall.getName());

                    if (apiCall.getParameters() != null && !apiCall.getParameters().isEmpty()) {
                        var schemaBuilder = JsonObjectSchema.builder();
                        for (var param : apiCall.getParameters().entrySet()) {
                            schemaBuilder.addStringProperty(param.getKey(), param.getValue() != null ? param.getValue() : param.getKey());
                        }
                        schemaBuilder.required(new ArrayList<>(apiCall.getParameters().keySet()));
                        specBuilder.parameters(schemaBuilder.build());
                    }

                    toolSpecs.add(specBuilder.build());

                    // Record what this tool actually calls, so approval patterns can be
                    // written against the endpoint rather than the generated name.
                    var apiRequest = apiCall.getRequest();
                    if (apiRequest != null && apiRequest.getMethod() != null && apiRequest.getPath() != null) {
                        endpoints.put(apiCall.getName(),
                                apiRequest.getMethod().toLowerCase(Locale.ROOT) + ":" + normalizeEndpointPath(apiRequest.getPath()));
                    }

                    // Resolving and executing MUST build their template data the same
                    // way: the fingerprint pinned at gate time is only meaningful if it
                    // describes the request execution will actually construct.
                    resolvers.put(apiCall.getName(),
                            toolRequest -> apiCallExecutor.resolve(apiCall, memory, templateDataFor(memory, toolRequest), targetServerUrl));

                    executors.put(apiCall.getName(), (toolRequest, memoryId) -> {
                        try {
                            Map<String, Object> templateData = templateDataFor(memory, toolRequest);

                            // Checked before the wire, not after. A body the model wrote as
                            // broken JSON cannot bind at any API, so sending it spends a round
                            // trip to be told something the string itself already says.
                            String rejection = rejectUnparseableBody(apiCall, templateData);
                            if (rejection != null) {
                                return rejection;
                            }

                            Map<String, Object> result = apiCallExecutor.execute(apiCall, memory, templateData, targetServerUrl);

                            String serialized = jsonSerialization.serialize(result);
                            LOGGER.info("Httpcall tool '" + apiCall.getName() + "' result: keys=" + result.keySet() + " size=" + serialized.length());
                            return serialized;
                        } catch (Exception e) {
                            LOGGER.error("Error executing httpcall tool '" + apiCall.getName() + "'", e);
                            return errorResult(e.getMessage() != null ? e.getMessage() : "Unknown error");
                        }
                    });
                }
            }

            LOGGER.info("Discovered " + toolSpecs.size() + " httpcall tools from workflow");
        } catch (Exception e) {
            LOGGER.warn("Failed to discover httpcall tools from workflow", e);
        }

        return new ToolContribution(toolSpecs, executors, Map.of(), endpoints, List.of(), Map.of(), resolvers);
    }

    /**
     * Template data for one httpcall tool invocation: conversation memory plus the
     * model's arguments merged over it.
     * <p>
     * Shared by the executor and the resolver on purpose. The gate-time fingerprint
     * only means anything if it was computed from the same inputs execution will
     * use — two copies of this merge would eventually disagree, and the guard would
     * then reject correct calls (or, worse, pass altered ones).
     */
    private Map<String, Object> templateDataFor(IConversationMemory memory, ToolExecutionRequest toolRequest) {
        Map<String, Object> templateData = memoryItemConverter.convert(memory);
        if (toolRequest.arguments() != null && !toolRequest.arguments().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = jsonSerialization.deserialize(toolRequest.arguments(), Map.class);
                safeTemplateMerge(templateData, args);
            } catch (IOException e) {
                // Redacted and capped, never raw: these are model-supplied arguments
                // that routinely carry credentials — the pause record keeps only a
                // SecretRedactionFilter'd copy for exactly this reason, and a log line
                // is no safer a place for the plaintext than that record was.
                //
                // The throwable is deliberately NOT passed: a Jackson parse error
                // quotes the offending source in its own message, which would undo the
                // redaction on the line right next to it. See errorType.
                // Order is load-bearing. Redact FIRST, on the full string: capping
                // first would cut a credential mid-token, and the fragment left behind
                // no longer matches the shape rules — a partial secret in the log
                // instead of a marker. Sanitize LAST: these arguments are model-chosen
                // and therefore prompt-injectable, and SecretRedactionFilter only
                // substitutes secret-shaped VALUES — it leaves \r and \n untouched, so
                // a model could forge whole log records in the HITL audit stream. The
                // tool name beside it was already sanitized for exactly this reason;
                // the argument string is the more attacker-controllable of the two.
                LOGGER.warnf("Failed to parse arguments for tool '%s' (%s): %s", sanitize(toolRequest.name()), errorType(e),
                        sanitize(capUtf8(SecretRedactionFilter.redact(toolRequest.arguments()), ARGS_LOG_MAX_BYTES)));
            }
        }
        return templateData;
    }

    /**
     * Refuse a call whose model-written request body is not JSON at all, and say
     * why, instead of sending it.
     * <p>
     * <b>Why this is the model's mistake and not a bug to fix elsewhere.</b>
     * {@code McpApiToolBuilder.buildBodyTemplate} generates the body as ONE
     * variable, {@code {requestBody}} — the model writes the entire JSON document
     * itself and EDDI puts nothing between that string and the wire, deliberately
     * (see that method: per-property templates were rejected because the templating
     * engine runs in TEXT mode and escapes nothing, so a substituted value carrying
     * a quote could break the body or add undeclared fields). So a body that fails
     * to bind failed because the model emitted invalid JSON — overwhelmingly a raw
     * newline or an unescaped quote inside a long string value, where {@code \n}
     * and {@code \"} were needed. Nothing here escapes it for them; the fix is for
     * the model to re-send, and this tells it so in the one place it will read.
     * <p>
     * Scoped narrowly, because a guard that refuses a call it merely fails to
     * understand is worse than the round trip it saves. It fires only when all of:
     * the call declares a JSON content type; its body template is nothing but a
     * single variable, so that variable IS the request body and judging the
     * variable judges the request (a hand-authored apicallstore template that
     * interpolates values into surrounding JSON is left alone — there the braces
     * around it are EDDI's, not the model's); and the variable actually resolved to
     * a non-blank string. The variable is read out of the template rather than
     * assumed to be named {@code requestBody}, because the builder renames it when
     * a path or query parameter already claims that name.
     * <p>
     * A blank value is deliberately NOT refused. That is the separate "model never
     * filled the body" failure — Qute renders an unsupplied variable as empty with
     * strict rendering off — and it is not a malformed document.
     *
     * @return {@code null} when there is nothing to refuse, otherwise the tool
     *         result to hand back in place of making the call
     */
    private String rejectUnparseableBody(ApiCall apiCall, Map<String, Object> templateData) {
        var request = apiCall.getRequest();
        if (request == null || request.getBody() == null || !declaresJsonBody(request.getContentType())) {
            return null;
        }
        var matcher = WHOLE_BODY_TEMPLATE.matcher(request.getBody().trim());
        if (!matcher.matches()) {
            return null;
        }
        // The real parameter name, not the literal "requestBody": the builder
        // renames the whole-body variable on a collision with a path or query
        // parameter, and telling the model to fix an argument its tool does not
        // expose is worse than saying nothing.
        String bodyParameter = matcher.group(1);
        Object value = templateData.get(bodyParameter);

        // A model that answered the "a single JSON object" parameter description
        // with an actual object rather than a string lands here as a Map. Qute runs
        // in TEXT mode, so it renders via toString — "{name=x}", which is not JSON
        // under any parser. This is at least as common as the escaping bug the
        // guard was written for, and refusing it by name is far more useful than
        // letting the API answer with a bind error about a body nobody can explain.
        // Numbers and booleans are left alone: they render as valid JSON scalars.
        if (value instanceof Map || value instanceof Iterable) {
            LOGGER.warnf("Refusing httpcall tool '%s' before sending: requestBody arrived as %s, not a string",
                    sanitize(apiCall.getName()), value.getClass().getSimpleName());
            return errorResult(bodyParameter + " must be a JSON document encoded as a STRING, but arrived as a "
                    + (value instanceof Map ? "JSON object" : "JSON array")
                    + ". The request was NOT sent. Send the whole body as one string value —"
                    + " \"{\\\"name\\\": \\\"…\\\"}\" — not as structured arguments.");
        }
        if (!(value instanceof String body) || body.isBlank()) {
            return null;
        }
        try {
            // STRICT_JSON, not the shared mapper: Jackson's default stops at the
            // first complete value and ignores the rest, so a body with a trailing
            // sentence or a closing ``` fence — the second-most-common shape of
            // this bug — parsed clean here and then failed to bind at the API,
            // leaving the model with EDDI's assurance that its body was fine.
            STRICT_JSON.readValue(body, Object.class);
            return null;
        } catch (IOException e) {
            String detail = parseFailureDetail(e);
            // The tool name and the parse detail, never the body: it is
            // model-supplied and routinely carries resolved secrets, which is why
            // the pause record keeps only a redacted copy (RequestRedactor) and why
            // templateDataFor's own catch goes to such lengths.
            LOGGER.warnf("Refusing httpcall tool '%s' before sending: the model's request body is not valid JSON (%s)",
                    sanitize(apiCall.getName()), detail);
            return errorResult(bodyParameter + " is not valid JSON: " + detail
                    + ". The request was NOT sent. Re-send this call with the body as one correctly escaped JSON"
                    + " document, and nothing after it: inside a string value a newline must be written \\n and a"
                    + " double quote \\\", never as a raw character.");
        }
    }

    /**
     * Whether this call's configured content type means "the body is a JSON
     * document".
     * <p>
     * Matched on the media type alone, with parameters stripped, rather than by
     * substring. {@code contains("application/json")} was both too loose and too
     * tight: it classified {@code multipart/related; type="application/json"} as
     * JSON — so a multipart body would have been refused — while missing
     * {@code application/problem+json}, {@code application/merge-patch+json} and
     * every other structured-suffix type, silently switching the guard off for
     * them.
     */
    static boolean declaresJsonBody(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals("application/json") || mediaType.equals("text/json") || mediaType.endsWith("+json");
    }

    /**
     * What went wrong and where, carrying no part of the body.
     *
     * <p>
     * <b>Built from an allow-list, not from the parser's message.</b> An earlier
     * version returned {@code getOriginalMessage()} on the reasoning that it is
     * snippet-free — which is true only of the {@code [Source: …]} suffix
     * {@code getMessage()} appends. The message ITSELF quotes model-controlled
     * input: a stray token produces {@code Unrecognized token 'SUPERSECRET'}, and
     * this string is both logged AND returned as a tool result that is persisted
     * into conversation memory. That is precisely the leak the whole method is
     * written to avoid, so the parser's own words never reach either.
     * <p>
     * What is returned instead is a fixed sentence chosen by exception type, plus
     * the numeric line and column. Those cover the failures a model actually
     * produces and are the part that makes the message actionable; an unrecognised
     * type degrades to a generic reason rather than to the parser's text.
     *
     * @return a body-free description of the parse failure
     */
    private static String parseFailureDetail(IOException e) {
        // Plain instanceof rather than a switch with pattern labels: only the TYPE
        // picks the sentence, nothing here reads the matched value. A pattern label
        // has to bind something, and both a named binding and the unnamed `_` are
        // reported by the CodeQL "unread local variable" query - the same style the
        // position lookup below already uses says it without the dead binding.
        String reason;
        if (e instanceof JsonParseException) {
            reason = "the document is malformed — an unescaped character, an unquoted token, or a missing delimiter";
        } else if (e instanceof MismatchedInputException) {
            reason = "the document is empty or ends before it is complete";
        } else {
            reason = "the document could not be parsed";
        }
        String position = "";
        if (e instanceof JsonProcessingException jsonError) {
            JsonLocation location = jsonError.getLocation();
            // JsonLocation.NA reports -1 for both.
            if (location != null && location.getLineNr() > 0) {
                position = " at line " + location.getLineNr() + ", column " + location.getColumnNr();
            }
        }
        return reason + position;
    }

    /**
     * The single tool-result shape for a call that did not produce a response — one
     * JSON object with an {@code error} string, escaped so the message cannot break
     * out of it.
     * <p>
     * Shared by the executor's catch-all and by {@link #rejectUnparseableBody} so a
     * refusal is indistinguishable in shape from a failure: both are "no response,
     * here is why", and a model that handles one handles the other.
     */
    private static String errorResult(String message) {
        return "{\"error\": \"" + new String(JsonStringEncoder.getInstance().quoteAsString(message)) + "\"}";
    }

    /**
     * The only part of a failure from the request-resolution path that is safe to
     * log: its type.
     * <p>
     * Not the throwable and not its message. These failures come out of template
     * rendering and request building, so the message routinely quotes the material
     * being rendered — Jackson in particular appends a snippet of the offending
     * source, which puts the credential straight back into the log line that
     * carefully redacted it. The type alone is what an operator triages on; the
     * payload is already available, redacted, in the same message.
     */
    private static String errorType(Throwable e) {
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    /** @see ToolApprovalGateSupport#capUtf8 */
    private static String capUtf8(String s, int maxBytes) {
        return ToolApprovalGateSupport.capUtf8(s, maxBytes);
    }

    /**
     * Normalise a configured path to the shape an approval pattern is written in.
     * <p>
     * The config accepts three shapes for the same endpoint — {@code /a/b},
     * {@code a/b}, and an absolute {@code https://host/a/b} (see
     * {@code ApiCallExecutor#buildRequest}, which applies the same leading-slash
     * rule). Storing the raw value would make {@code http.post:/a/b} miss two of
     * them, and a require-pattern that misses is an ungated write.
     * <p>
     * An absolute URL keeps only its path, so the same pattern matches however the
     * target server was configured.
     */
    static String normalizeEndpointPath(String rawPath) {
        String path = rawPath.trim();
        // Request.path defaults to "", and an empty path means the target server's
        // root — ApiCallExecutor sends the request to targetServerUrl unchanged.
        // Returning "" here would make the key "post:", which no pattern can match,
        // so a root endpoint would be silently ungateable.
        if (path.isEmpty()) {
            return "/";
        }
        // Only a real absolute URL, not merely a path that happens to begin "http".
        // ApiCallExecutor tests startsWith("http"), which is loose enough that a
        // relative path like "httpcalls/agents" would be parsed as a URI here — and an
        // opaque one collapses to an empty path, losing the endpoint entirely.
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                String extracted = URI.create(path).getPath();
                // A URL with no path ("https://host", "https://host?x=1") yields an
                // empty path. Left empty the endpoint key would be "post:", which no
                // pattern expecting a leading slash can match — and an unmatched
                // require-pattern is an ungated call. A root endpoint is "/".
                path = extracted == null || extracted.isEmpty() ? "/" : extracted;
            } catch (IllegalArgumentException e) {
                // Not parseable as a URI — a templated host, most likely. Leave it be:
                // matching something odd is better than throwing during discovery.
                LOGGER.debugf("Could not normalise endpoint path '%s' for approval matching", sanitize(rawPath));
                return path;
            }
        }
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    /**
     * Merge LLM tool arguments into template data, blocking any keys that collide
     * with internal pipeline data. Blocked keys are logged as warnings so config
     * authors can rename their parameters if needed.
     * <p>
     * Package-private (not private) so {@code AgentOrchestrator}'s declared
     * delegator — kept for the reflection-based characterization test — can call
     * it.
     */
    static void safeTemplateMerge(Map<String, Object> templateData, Map<String, Object> args) {
        for (var entry : args.entrySet()) {
            if (RESERVED_TEMPLATE_KEYS.contains(entry.getKey())) {
                LOGGER.warnf("Blocked LLM tool argument '%s' — collides with reserved template key. " +
                        "Rename the httpcall parameter to avoid this conflict.", entry.getKey());
                continue;
            }
            templateData.put(entry.getKey(), entry.getValue());
        }
    }
}
