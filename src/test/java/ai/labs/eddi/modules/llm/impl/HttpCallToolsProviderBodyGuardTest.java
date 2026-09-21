/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pre-flight JSON check on a model-written request body.
 * <p>
 * <b>What this is defending.</b> An operator {@code setupAgent} call, already
 * approved by a human, came back {@code 400
 * {"objectName":"Class","attributeName":"systemPrompt","line":1,"column":593}}
 * — the body failed to bind, deep inside a long {@code systemPrompt} string.
 * Nothing in EDDI had mangled it: {@code McpApiToolBuilder} puts the whole body
 * in ONE variable precisely so there is no substitution boundary to cross, so
 * the string the model emitted is the string that went out, and it was not
 * valid JSON. The guard makes that failure arrive from EDDI, before the
 * request, naming the position — instead of from the target API, after it.
 * <p>
 * These tests drive the <em>real</em> executor lambda built by
 * {@code discover}, not a re-implementation of the check, and they use the real
 * {@link JsonSerialization} rather than a mock: a stubbed parser would prove
 * the stub throws, and the whole point is that genuinely malformed JSON is
 * caught. The assertion that carries the weight is
 * {@code verify(apiCallExecutor, never()).execute(...)} — the request must not
 * be sent.
 *
 * @author tests
 */
class HttpCallToolsProviderBodyGuardTest {

    private static final String AGENT_ID = "agent-1";
    private static final int AGENT_VERSION = 1;
    private static final String WORKFLOW_URI = "eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1";
    private static final String HTTPCALLS_URI = "eddi://ai.labs.httpcalls/apicallstore/apicalls/api-1?version=1";
    private static final String TOOL_NAME = "setupAgent";

    private IConversationMemory memory;
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IResourceClientLibrary resourceClientLibrary;
    private IApiCallExecutor apiCallExecutor;
    private IMemoryItemConverter memoryItemConverter;
    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void setUp() {
        // discover() traverses through WorkflowTraversal's process-wide cache; each
        // test here builds a DIFFERENT ApiCall under the same (agent, version, step
        // type) coordinates, which is exactly the shape a leaked entry answers stale.
        WorkflowTraversal.clearCache();
        memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(AGENT_ID);
        when(memory.getAgentVersion()).thenReturn(AGENT_VERSION);
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        apiCallExecutor = mock(IApiCallExecutor.class);
        memoryItemConverter = mock(IMemoryItemConverter.class);
        when(memoryItemConverter.convert(any())).thenReturn(new HashMap<>());
        jsonSerialization = new JsonSerialization(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        WorkflowTraversal.clearCache();
    }

    // =================================================================
    // The guard fires
    // =================================================================

    /**
     * The reported failure, reproduced exactly: the model escaped one level too
     * few. Its arguments are perfectly valid JSON — {@code "\n"} inside them is a
     * legal two-character escape — but that escape DECODES to a raw newline, and a
     * raw newline inside the string value of the inner document is not legal JSON.
     * The model needed to write {@code \\n} so the body itself kept an escape.
     * <p>
     * Note what this rules out: the arguments cannot themselves be malformed, or
     * {@code templateDataFor} would have failed to parse them and no body would
     * exist to check. This failure is only reachable through a well-formed
     * arguments object carrying a badly-formed body — which is why the fixtures
     * here build the arguments with the real serializer instead of hand-escaping
     * two nested levels and hoping.
     */
    @Test
    void rawNewlineInsideAStringValue_isRefusedWithoutSendingTheRequest() throws Exception {
        ToolExecutor executor = executorForJsonBodyTool();

        String result = executor.execute(argumentsCarryingBody(
                "{\"name\":\"Bot\",\"systemPrompt\":\"line one\nline two\"}"), "memory-1");

        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
        assertTrue(errorOf(result).startsWith("requestBody is not valid JSON"),
                "the model must be told which argument was wrong: " + result);
    }

    @Test
    void unescapedQuoteInsideAStringValue_isRefusedWithoutSendingTheRequest() throws Exception {
        ToolExecutor executor = executorForJsonBodyTool();

        String result = executor.execute(argumentsCarryingBody("{\"name\":\"the \"bot\"\"}"), "memory-1");

        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
        assertTrue(errorOf(result).startsWith("requestBody is not valid JSON"));
    }

    /** A truncated document — the other shape a long generated body arrives in. */
    @Test
    void aTruncatedBodyIsRefusedWithoutSendingTheRequest() throws Exception {
        ToolExecutor executor = executorForJsonBodyTool();

        String result = executor.execute(argumentsCarryingBody("{\"name\":\"Bot\",\"systemPrompt\":"), "memory-1");

        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
        assertTrue(errorOf(result).startsWith("requestBody is not valid JSON"));
    }

    /**
     * The message has one job: get the NEXT attempt right. It has to name the
     * argument, carry the parse position (the only part of the failure that
     * localises it in a body hundreds of characters long), say the request did not
     * go out — or the model reasonably assumes a partial write it must undo — and
     * say how to escape.
     */
    @Test
    void theRefusalTellsTheModelWhereAndHowToFixIt() throws Exception {
        ToolExecutor executor = executorForJsonBodyTool();

        String error = errorOf(executor.execute(
                argumentsCarryingBody("{\"systemPrompt\":\"line one\nline two\"}"), "memory-1"));

        assertTrue(error.contains(" at line ") && error.contains(", column "),
                "the parse position localises the failure: " + error);
        assertTrue(error.contains("was NOT sent"), "the model must know no partial write happened: " + error);
        assertTrue(error.contains("\\n"), "the escaping instruction is the actionable half: " + error);
    }

    /**
     * The body is model-supplied and routinely carries resolved secrets — the whole
     * reason {@code RequestRedactor} exists and {@code templateDataFor}'s own catch
     * redacts before logging. A refusal that echoed it would put the plaintext into
     * conversation memory, where the tool result is persisted.
     * <p>
     * This is also why the message is built from the parse LOCATION and not from
     * Jackson's own message, which appends a snippet of the offending source.
     */
    @Test
    void theRefusalNeverEchoesTheOffendingBody() throws Exception {
        ToolExecutor executor = executorForJsonBodyTool();

        String result = executor.execute(argumentsCarryingBody(
                "{\"apiKey\":\"sk-live-SUPERSECRET\",\"systemPrompt\":\"a\nb\"}"), "memory-1");

        // Read through errorOf first, which asserts a refusal actually happened.
        // Asserting only the absence of the secret would pass just as happily if
        // nothing were refused at all — a test green for the opposite reason.
        String error = errorOf(result);
        assertTrue(error.startsWith("requestBody is not valid JSON"));
        assertFalse(error.contains("SUPERSECRET"), "the refusal leaked the body: " + error);
        assertFalse(error.contains("apiKey"), "the refusal leaked the body: " + error);
    }

    /**
     * The builder renames the whole-body variable when a path or query parameter
     * already claims the name {@code requestBody}. The guard reads the variable out
     * of the template for exactly this reason — hardcoding the name would make the
     * check silently stop applying to those calls.
     */
    @Test
    void aRenamedWholeBodyVariableIsStillChecked() throws Exception {
        ToolExecutor executor = executorFor(jsonBodyCall("{requestBodyBody}"));

        String result = executor.execute(
                toolCall(jsonSerialization.serialize(Map.of("requestBodyBody", "{\"a\":\"x\ny\"}"))),
                "memory-1");

        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
        // Named for the parameter the tool ACTUALLY exposes. On this path there
        // is no "requestBody" argument at all, so telling the model to fix one
        // would send it looking for something that does not exist.
        assertTrue(errorOf(result).startsWith("requestBodyBody is not valid JSON"), errorOf(result));
    }

    /**
     * The refusal is assembled from an allow-list, never from the parser's own
     * message.
     * <p>
     * {@code getOriginalMessage()} looks safe — it omits the {@code [Source: …]}
     * suffix that {@code getMessage()} appends — but the message ITSELF quotes
     * model-controlled input: an unrecognised token yields
     * {@code Unrecognized token 'SUPERSECRET'}. That string is logged AND returned
     * as a tool result which lands in conversation memory, so echoing the parser
     * would leak exactly what this guard promises to withhold.
     */
    @Test
    void theRefusalNeverEchoesAnUnrecognisedTokenFromTheBody() throws Exception {
        ToolExecutor executor = executorForJsonBodyTool();

        // A bare token is what makes Jackson quote it back in its message.
        String result = executor.execute(argumentsCarryingBody("sk-live-SUPERSECRET"), "memory-1");

        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
        assertFalse(result.contains("SUPERSECRET"), "the parser's message leaked the body: " + result);
        assertFalse(result.contains("Unrecognized token"), "the parser's own words must not be echoed: " + result);
    }

    /**
     * Same again, for a secret sitting in trailing garbage after a valid document.
     */
    @Test
    void theRefusalNeverEchoesATrailingTokenFromTheBody() throws Exception {
        ToolExecutor executor = executorForJsonBodyTool();

        String result = executor.execute(argumentsCarryingBody("{\"a\":1} sk-live-SUPERSECRET"), "memory-1");

        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
        assertFalse(result.contains("SUPERSECRET"), "the parser's message leaked the body: " + result);
    }

    // =================================================================
    // The guard stays out of the way
    // =================================================================

    @Test
    void aValidBodyPassesStraightThroughUnchanged() throws Exception {
        when(apiCallExecutor.execute(any(), any(), any(), anyString())).thenReturn(Map.of("httpCode", 201));
        ToolExecutor executor = executorForJsonBodyTool();

        // The same body the model got wrong above, written correctly: the newline
        // stays an escape INSIDE the document rather than decoding to a raw one.
        String result = executor.execute(
                argumentsCarryingBody("{\"name\":\"Bot\",\"systemPrompt\":\"line one\\nline two\"}"), "memory-1");

        verify(apiCallExecutor, times(1)).execute(any(), any(), any(), anyString());
        assertTrue(result.contains("httpCode"), "the executor's own result must be returned verbatim: " + result);
    }

    /**
     * A GET has no body template at all — there is nothing to judge.
     * <p>
     * The content type is declared deliberately: {@code new Request()} defaults
     * BOTH {@code body} and {@code contentType} to {@code ""}, so a bare GET
     * fixture exits at the content-type gate and never reaches the body path this
     * test is named for. Declaring JSON forces it past that gate, so the empty body
     * is what actually decides.
     */
    @Test
    void aCallWithNoBodyIsUnaffected() throws Exception {
        when(apiCallExecutor.execute(any(), any(), any(), anyString())).thenReturn(Map.of("httpCode", 200));
        var request = new Request();
        request.setMethod("get");
        request.setPath("/agentstore/agents/descriptors");
        request.setContentType("application/json");
        ToolExecutor executor = executorFor(callWith(request));

        executor.execute(toolCall("{\"limit\":\"10\"}"), "memory-1");

        verify(apiCallExecutor, times(1)).execute(any(), any(), any(), anyString());
    }

    /**
     * Jackson stops at the first complete value by default and ignores the rest, so
     * this shape — a correct document with a sentence or a closing markdown fence
     * after it — used to validate clean and then fail to bind at the API. Worse
     * than not catching it: the model came away with EDDI's positive assurance that
     * its body was fine, making the next attempt less likely to fix it.
     */
    @Test
    void trailingProseAfterTheDocumentIsRefused() throws Exception {
        ToolExecutor executor = executorForJsonBodyTool();

        String result = executor.execute(
                argumentsCarryingBody("{\"name\":\"Bot\"}\n\nSure, I created the agent!"), "memory-1");

        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
        assertTrue(errorOf(result).startsWith("requestBody is not valid JSON"));
    }

    @Test
    void aTrailingMarkdownFenceIsRefused() throws Exception {
        ToolExecutor executor = executorForJsonBodyTool();

        executor.execute(argumentsCarryingBody("{\"name\":\"Bot\"}\n```"), "memory-1");

        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
    }

    /**
     * The parameter description says "a single JSON object", and a model that
     * answers it with an actual object rather than a string is at least as common
     * as the escaping bug. Qute renders in TEXT mode, so the Map would reach the
     * wire as {@code {name=Bot}} — not JSON under any parser — and the API would
     * answer with a bind error nothing in the arguments explains.
     */
    @Test
    void aStructuredObjectInsteadOfAStringIsRefusedByName() throws Exception {
        ToolExecutor executor = executorForJsonBodyTool();

        String result = executor.execute(
                toolCall(jsonSerialization.serialize(Map.of("requestBody", Map.of("name", "Bot")))), "memory-1");

        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
        String error = errorOf(result);
        assertTrue(error.contains("STRING"), "the model must be told which shape it got wrong: " + error);
        assertTrue(error.contains("JSON object"), error);
        assertFalse(error.contains("Bot"), "the refusal must not echo the body: " + error);
    }

    /**
     * A structured-suffix JSON type is still JSON. The old
     * {@code contains("application/json")} test missed every one of these, silently
     * switching the guard off for them.
     */
    @Test
    void aStructuredSuffixJsonContentTypeIsStillGuarded() throws Exception {
        var request = new Request();
        request.setMethod("patch");
        request.setPath("/things/{id}");
        request.setContentType("application/merge-patch+json");
        request.setBody("{requestBody}");
        ToolExecutor executor = executorFor(callWith(request));

        String result = executor.execute(argumentsCarryingBody("{\"a\":\"x\ny\"}"), "memory-1");

        verify(apiCallExecutor, never()).execute(any(), any(), any(), anyString());
        assertTrue(errorOf(result).startsWith("requestBody is not valid JSON"));
    }

    /**
     * ...and a multipart body that merely NAMES json in a parameter is not a JSON
     * document. The old substring test classified this as JSON and would have
     * refused a working call.
     */
    @Test
    void aMultipartContentTypeNamingJsonIsNotGuarded() throws Exception {
        when(apiCallExecutor.execute(any(), any(), any(), anyString())).thenReturn(Map.of("httpCode", 200));
        var request = new Request();
        request.setMethod("post");
        request.setPath("/upload");
        request.setContentType("multipart/related; type=\"application/json\"");
        request.setBody("{requestBody}");
        ToolExecutor executor = executorFor(callWith(request));

        executor.execute(argumentsCarryingBody("--boundary not json at all"), "memory-1");

        verify(apiCallExecutor, times(1)).execute(any(), any(), any(), anyString());
    }

    /** The media type alone decides; parameters are not part of it. */
    @Test
    void jsonContentTypeClassification() {
        assertTrue(HttpCallToolsProvider.declaresJsonBody("application/json; charset=utf-8"));
        assertTrue(HttpCallToolsProvider.declaresJsonBody("APPLICATION/JSON"));
        assertTrue(HttpCallToolsProvider.declaresJsonBody("application/problem+json"));
        assertFalse(HttpCallToolsProvider.declaresJsonBody("text/plain"));
        assertFalse(HttpCallToolsProvider.declaresJsonBody(null));
        assertFalse(HttpCallToolsProvider.declaresJsonBody("multipart/related; type=\"application/json\""));
    }

    /**
     * A non-JSON content type is not a JSON document, so "is this valid JSON" is
     * the wrong question. {@code POST /agents/{conversationId}} consumes
     * {@code text/plain} — refusing a plain-text message because it does not parse
     * as JSON would break a working call.
     */
    @Test
    void aNonJsonContentTypeIsUnaffected() throws Exception {
        when(apiCallExecutor.execute(any(), any(), any(), anyString())).thenReturn(Map.of("httpCode", 200));
        var request = new Request();
        request.setMethod("post");
        request.setPath("/agents/{conversationId}");
        request.setContentType("text/plain");
        request.setBody("{requestBody}");
        ToolExecutor executor = executorFor(callWith(request));

        executor.execute(toolCall("{\"requestBody\":\"just a sentence, not JSON\"}"), "memory-1");

        verify(apiCallExecutor, times(1)).execute(any(), any(), any(), anyString());
    }

    /**
     * A hand-authored apicallstore template interpolates model values INTO JSON
     * that EDDI wrote. The braces are not the model's, one value is not a document,
     * and judging it as one would refuse every correct call of this shape — by far
     * the more common config in the wild.
     */
    @Test
    void aPerPropertyTemplateIsUnaffected() throws Exception {
        when(apiCallExecutor.execute(any(), any(), any(), anyString())).thenReturn(Map.of("httpCode", 200));
        ToolExecutor executor = executorFor(jsonBodyCall("{\"name\":\"{name}\"}"));

        executor.execute(toolCall("{\"name\":\"Bot\"}"), "memory-1");

        verify(apiCallExecutor, times(1)).execute(any(), any(), any(), anyString());
    }

    /**
     * An unsupplied variable renders empty (Qute, strict rendering off). That is
     * the separate "the model never filled the body" failure, not a malformed
     * document, and it is left to the API — which now reports its 400 back through
     * the result map.
     */
    @Test
    void anAbsentOrBlankBodyIsLeftToTheApi() throws Exception {
        when(apiCallExecutor.execute(any(), any(), any(), anyString())).thenReturn(Map.of("httpCode", 400));
        ToolExecutor executor = executorForJsonBodyTool();

        executor.execute(toolCall("{\"environment\":\"production\"}"), "memory-1");
        executor.execute(toolCall("{\"requestBody\":\"   \"}"), "memory-1");

        verify(apiCallExecutor, times(2)).execute(any(), any(), any(), anyString());
    }

    /**
     * The guard runs on the resolved template data, so it must not fire on a value
     * that came from conversation memory rather than from the model — and it must
     * not fire on a valid body that merely happens to be an array or a scalar, both
     * of which are legitimate JSON documents.
     */
    @Test
    void aValidNonObjectBodyIsAccepted() throws Exception {
        when(apiCallExecutor.execute(any(), any(), any(), anyString())).thenReturn(Map.of("httpCode", 200));
        ToolExecutor executor = executorForJsonBodyTool();

        executor.execute(argumentsCarryingBody("[{\"a\":1},{\"a\":2}]"), "memory-1");

        verify(apiCallExecutor, times(1)).execute(any(), any(), any(), anyString());
    }

    // =================================================================
    // Helpers
    // =================================================================

    private ToolExecutor executorForJsonBodyTool() throws Exception {
        return executorFor(jsonBodyCall("{requestBody}"));
    }

    /**
     * Tool arguments carrying {@code body} as the whole-body variable, serialized
     * for real.
     * <p>
     * Hand-writing two nested levels of JSON escaping in a Java string literal is
     * three escapes deep and gets it wrong silently: a mistake at the OUTER level
     * makes the arguments unparseable, {@code templateDataFor} drops them, and the
     * guard is never reached — a test that passes because nothing happened. Letting
     * the serializer own the outer level makes {@code body} exactly the string the
     * model meant to send, written plainly.
     */
    private ToolExecutionRequest argumentsCarryingBody(String body) throws Exception {
        return toolCall(jsonSerialization.serialize(Map.of("requestBody", body)));
    }

    private ToolExecutor executorFor(ApiCall apiCall) throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create(WORKFLOW_URI)));
        when(agentStore.read(AGENT_ID, AGENT_VERSION)).thenReturn(agentConfig);

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.httpcalls"));
        step.setConfig(Map.of("uri", HTTPCALLS_URI));
        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of(step));
        when(workflowStore.read("wf-1", 1)).thenReturn(workflowConfig);

        var httpCallsConfig = new ApiCallsConfiguration();
        httpCallsConfig.setTargetServerUrl("https://eddi.example");
        httpCallsConfig.setHttpCalls(List.of(apiCall));
        when(resourceClientLibrary.getResource(eq(URI.create(HTTPCALLS_URI)), eq(ApiCallsConfiguration.class)))
                .thenReturn(httpCallsConfig);

        var provider = new HttpCallToolsProvider(agentStore, workflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter);
        ToolExecutor executor = provider.discover(memory).executors().get(TOOL_NAME);
        assertNotNull(executor, "discovery did not produce the tool under test");
        return executor;
    }

    /**
     * The shape {@code McpApiToolBuilder} generates for a JSON-bodied operation.
     */
    private static ApiCall jsonBodyCall(String bodyTemplate) {
        var request = new Request();
        request.setMethod("post");
        request.setPath("/administration/agents/setup");
        request.setContentType("application/json");
        request.setBody(bodyTemplate);
        return callWith(request);
    }

    private static ApiCall callWith(Request request) {
        var apiCall = new ApiCall();
        apiCall.setName(TOOL_NAME);
        apiCall.setRequest(request);
        return apiCall;
    }

    private static ToolExecutionRequest toolCall(String arguments) {
        return ToolExecutionRequest.builder().id("call-1").name(TOOL_NAME).arguments(arguments).build();
    }

    /** The {@code error} value out of the tool result, unescaped. */
    private String errorOf(String result) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonSerialization.deserialize(result, Map.class);
        Object error = parsed.get("error");
        assertNotNull(error, "expected an error result, got: " + result);
        assertEquals(1, parsed.size(), "an error result carries nothing but the message: " + result);
        return (String) error;
    }
}
