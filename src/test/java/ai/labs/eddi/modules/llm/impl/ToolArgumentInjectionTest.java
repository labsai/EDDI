/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.ForEachInstruction;
import ai.labs.eddi.configs.apicalls.model.HttpPostResponse;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.apicalls.model.RetryApiCallInstruction;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.httpclient.ICompleteListener;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.apicalls.impl.ApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.apicalls.impl.RequestRedactor;
import ai.labs.eddi.modules.apicalls.impl.ResolvedRequest;
import ai.labs.eddi.modules.apicalls.impl.ToolArgumentBodyEscaper.ArgumentRefusedException;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.templating.impl.ConfigReferenceNamespaceResolvers;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Model-supplied tool arguments cannot change the structure of the JSON an
 * httpcall sends — proven through the real path: the tool executor built by
 * {@link HttpCallToolsProvider} ({@code templateDataFor}), the real
 * {@link ApiCallExecutor} ({@code buildRequest}) and the real templating
 * engine. Only the HTTP client is a double, and it records what is sent.
 * <p>
 * Every case asserts one property: either the call is refused (nothing is sent,
 * and the answer names no value), or the body sent for the hostile arguments
 * parses as JSON and is structurally identical to the body sent for benign
 * arguments of the same shape — same node kinds, same author keys, same array
 * lengths, author-written strings unchanged; only argument-controlled leaves
 * differ. Each case also states which of the two outcomes it expects, so a
 * guard that refused everything would fail as surely as one that let an
 * injection through.
 */
class ToolArgumentInjectionTest {

    private static final String TOOL = "callApi";
    private static final String AGENT_ID = "agent-1";
    private static final String WORKFLOW_URI = "eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1";
    private static final String HTTPCALLS_URI = "eddi://ai.labs.httpcalls/apicallstore/apicalls/api-1?version=1";
    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * Parses a sent body as one whole document, as the production guard does:
     * {@code {"name":"x"} {"admin":true}} is not JSON, not its first value.
     */
    private static final ObjectMapper WHOLE_DOCUMENT = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String LONG = "x".repeat(20_000) + "\",\"admin\":true,\"z\":\"";

    enum Expect {
        SENT, REFUSED
    }

    record Case(String name, String body, Map<String, Object> schema, String hostile, String benign, Expect expect, Consumer<ApiCall> customize) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Case sent(String name, String body, String hostile, String benign) {
        return new Case(name, body, null, hostile, benign, Expect.SENT, null);
    }

    private static Case refused(String name, String body, String hostile, String benign) {
        return new Case(name, body, null, hostile, benign, Expect.REFUSED, null);
    }

    private static Case typed(String name, String body, String schema, String hostile, String benign, Expect expect) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(schema, Map.class);
            return new Case(name, body, parsed, hostile, benign, expect, null);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    static Stream<Case> cases() {
        String smuggle = "\"x\\\",\\\"role\\\":\\\"admin\"";
        String tags = "[\"a\\\"],\\\"role\\\":\\\"admin\\\",\\\"z\\\":[\\\"b\",\"c\"]";
        String benignTags = "[\"benign1\",\"benign2\"]";
        return Stream.of(
                // --- the headline and plain string positions
                sent("string literal", "{\"name\":\"{name}\"}", "{\"name\":" + smuggle + "}", "{\"name\":\"benign\"}"),
                sent("string with surrounding text, used twice", "{\"a\":\"<{name}>\",\"b\":\"{name}\"}",
                        "{\"name\":\"\\\"}, \\\"c\\\": {\\\"\"}", "{\"name\":\"benign\"}"),
                sent("quotes and backslashes", "{\"name\":\"{name}\"}", "{\"name\":\"\\\\\\\"\\\\\\\\\\\"\"}", "{\"name\":\"benign\"}"),
                sent("NUL and control characters", "{\"name\":\"{name}\"}", "{\"name\":\"a\\u0000b\\u001fc\\n\\r\"}", "{\"name\":\"benign\"}"),
                sent("U+2028 and U+2029", "{\"name\":\"{name}\"}", "{\"name\":\"a\\u2028b\\u2029c\"}", "{\"name\":\"benign\"}"),
                sent("closing brackets", "{\"name\":\"{name}\",\"n\":1}", "{\"name\":\"]}]}\"}", "{\"name\":\"benign\"}"),
                sent("very long value", "{\"name\":\"{name}\"}", "{\"name\":\"" + LONG.replace("\"", "\\\"") + "\"}", "{\"name\":\"benign\"}"),
                sent("null in a string", "{\"name\":\"{name}\"}", "{\"name\":null}", "{\"name\":\"benign\"}"),
                // --- aliases and namespaces (review M1)
                sent("each alias in a string", "{\"tags\":[{#each tags}\"{it}\"{#if it_hasNext},{/if}{/each}]}", "{\"tags\":" + tags + "}",
                        "{\"tags\":" + benignTags + "}"),
                sent("let alias in a string", "{\"q\":\"{#let v=name}{v}{/let}\"}", "{\"name\":" + smuggle + "}", "{\"name\":\"benign\"}"),
                sent("for alias in a string", "{\"q\":\"{#for t in tags}{t}{/for}\"}", "{\"tags\":" + tags + "}", "{\"tags\":" + benignTags + "}"),
                sent("data namespace in a string", "{\"q\":\"{data:name}\"}", "{\"name\":" + smuggle + "}", "{\"name\":\"benign\"}"),
                sent("dollar before the brace", "{\"q\":\"${name}\"}", "{\"name\":" + smuggle + "}", "{\"name\":\"benign\"}"),
                sent("with section", "{#with meta}{\"a\":\"{x}\"}{/with}", "{\"meta\":{\"x\":" + smuggle + "}}", "{\"meta\":{\"x\":\"benign\"}}"),
                sent("map keys iterated in a string", "{\"q\":\"{#for k in meta.keys}{k};{/for}\"}",
                        "{\"meta\":{\"x\\\",\\\"admin\\\":true,\\\"y\\\":\\\"\":\"1\"}}", "{\"meta\":{\"benignK\":\"1\"}}"),
                sent("nested property", "{\"who\":\"{user.name}\"}", "{\"user\":{\"name\":" + smuggle + "}}", "{\"user\":{\"name\":\"benign\"}}"),
                // --- map and list text (review M2)
                sent("map toString in a string", "{\"note\":\"{meta}\"}", "{\"meta\":{\"x\\\",\\\"admin\\\":true,\\\"y\\\":\\\"\":\"1\"}}",
                        "{\"meta\":{\"benignK\":\"1\"}}"),
                sent("list toString in a string", "{\"note\":\"{tags}\"}", "{\"tags\":" + tags + "}", "{\"tags\":" + benignTags + "}"),
                // --- bare positions (review M3)
                sent("bare plain string is quoted", "{\"name\":{name}}", "{\"name\":" + smuggle + "}", "{\"name\":\"benign\"}"),
                sent("bare closing brackets are quoted", "{\"name\":{name}}", "{\"name\":\"]}\"}", "{\"name\":\"benign\"}"),
                refused("bare compound default", "{\"ids\":[{id ?: 0}]}", "{\"id\":\"1,2,3\"}", "{\"id\":\"7\"}"),
                sent("bare compound default with a number", "{\"ids\":[{id ?: 0}]}", "{\"id\":7}", "{\"id\":8}"),
                refused("bare method call", "{\"n\":{n.or(10)}}", "{\"n\":\"1,\\\"admin\\\":true\"}", "{\"n\":\"7\"}"),
                sent("bare trailing space is plain", "{\"n\":{n }}", "{\"n\":\"1,\\\"admin\\\":true\"}", "{\"n\":\"benign\"}"),
                refused("bare let alias", "{\"v\":{#let v=name}{v}{/let}}", "{\"name\":\"1,\\\"admin\\\":true\"}", "{\"name\":\"7\"}"),
                refused("bare each alias with hostile items", "{\"ids\":[{#each ids}{it}{#if it_hasNext},{/if}{/each}]}",
                        "{\"ids\":[\"1,2\",\"3\"]}", "{\"ids\":[\"7\",\"8\"]}"),
                sent("bare each alias with numbers", "{\"ids\":[{#each ids}{it}{#if it_hasNext},{/if}{/each}]}", "{\"ids\":[1,2,3]}",
                        "{\"ids\":[7,8,9]}"),
                sent("bare data namespace is plain", "{\"n\":{data:n}}", "{\"n\":\"1,\\\"admin\\\":true\"}", "{\"n\":\"benign\"}"),
                sent("bare object with hostile keys", "{\"filter\":{filter}}", "{\"filter\":{\"x\\\",\\\"admin\\\":true,\\\"y\\\":\\\"\":\"1\"}}",
                        "{\"filter\":{\"benignK\":\"1\"}}"),
                sent("bare array of hostile strings", "{\"tags\":{tags}}", "{\"tags\":" + tags + "}", "{\"tags\":" + benignTags + "}"),
                sent("bare number as a string", "{\"count\":{count}}", "{\"count\":\"5\"}", "{\"count\":\"7\"}"),
                sent("bare boolean", "{\"on\":{on}}", "{\"on\":true}", "{\"on\":false}"),
                sent("bare null", "{\"name\":{name}}", "{\"name\":null}", "{\"name\":null}"),
                refused("bare non-finite number", "{\"n\":{n}}", "{\"n\":\"1e999\"}", "{\"n\":\"7\"}"),
                sent("bare deep nesting", "{\"filter\":{filter}}", "{\"filter\":" + nested(40, "\"a\\\"],\\\"b\\\":[\\\"\"") + "}",
                        "{\"filter\":" + nested(40, "\"benign\"") + "}"),
                sent("bare reserved-looking argument cannot override", "{\"name\":{name}}",
                        "{\"name\":\"benign\",\"eddiJsonVal_name\":\"1,\\\"admin\\\":true\"}", "{\"name\":\"benign\",\"eddiJsonVal_name\":\"x\"}"),
                // --- scanner state (review notes)
                sent("comment holding a quote", "{! say \"hi !}{\"a\":\"{name}\"}", "{\"name\":" + smuggle + "}", "{\"name\":\"benign\"}"),
                sent("unparsed block opening a string", "{\"a\":1,{|\"x\":\"|}{name}\"}", "{\"name\":" + smuggle + "}", "{\"name\":\"benign\"}"),
                sent("vault reference in a bare position", "{\"a\":\"{name}\",\"k\":${vault:limit}}", "{\"name\":" + smuggle + "}",
                        "{\"name\":\"benign\"}"),
                // --- references resolved after the check (CodeRabbit 4621747966)
                refused("variable reference in a string literal", "{\"q\":\"{name}\"}", "{\"name\":\"${vars:greeting}\"}",
                        "{\"name\":\"benign\"}"),
                refused("variable reference assembled from two arguments", "{\"q\":\"{a}{b}\"}", "{\"a\":\"$\",\"b\":\"{vars:greeting}\"}",
                        "{\"a\":\"benign\",\"b\":\"benign\"}"),
                refused("argument-supplied vault reference in a bare position", "{\"q\":{name}}", "{\"name\":\"${vault:limit}\"}",
                        "{\"name\":\"benign\"}"),
                // --- an argument that removes the whole document (CodeRabbit 4621748044)
                refused("section holding the document switched off", "{#if on}{\"a\":1}{/if}", "{\"on\":false}", "{\"on\":true}"),
                // --- legitimate templates keep working
                sent("conditional on a plain value", "{\"sort\":\"{#if order == 'asc'}ascending{#else}descending{/if}\"}", "{\"order\":\"asc\"}",
                        "{\"order\":\"asc\"}"),
                sent("conditional on a value with a space", "{#if mode == 'express delivery'}{\"fast\":true}{#else}{\"fast\":false,\"x\":1}{/if}",
                        "{\"mode\":\"express delivery\"}", "{\"mode\":\"express delivery\"}"),
                sent("conditional section in a bare position", "{\"n\":{#if flag}1{#else}2{/if}}", "{\"flag\":\"yes\"}", "{\"flag\":\"yes\"}"),
                sent("author data next to arguments", "{\"name\":\"{name}\",\"role\":\"{properties.role}\"}", "{\"name\":" + smuggle + "}",
                        "{\"name\":\"benign\"}"),
                // --- typed calls (review S2, S3)
                typed("typed string bare", "{\"name\":{name}}", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}",
                        "{\"name\":\"1,\\\"admin\\\":true\"}", "{\"name\":\"benign\"}", Expect.SENT),
                typed("typed integer refuses a smuggle", "{\"count\":{count}}",
                        "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}}}", "{\"count\":\"1,2,3\"}", "{\"count\":7}",
                        Expect.REFUSED),
                typed("typed string-or-null keeps a JSON-looking string a string", "{\"x\":{x}}",
                        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":[\"string\",\"null\"]}}}", "{\"x\":\"{\\\"admin\\\":true}\"}",
                        "{\"x\":\"benign\"}", Expect.SENT),
                typed("typed anyOf string", "{\"x\":{x}}",
                        "{\"type\":\"object\",\"properties\":{\"x\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}}}",
                        "{\"x\":\"5,\\\"a\\\":1\"}", "{\"x\":\"benign\"}", Expect.SENT),
                typed("typed enum only", "{\"mode\":{mode}}", "{\"type\":\"object\",\"properties\":{\"mode\":{\"enum\":[\"benign\",\"b\"]}}}",
                        "{\"mode\":\"b\"}", "{\"mode\":\"benign\"}", Expect.SENT),
                typed("typed object with a hostile string inside", "{\"filter\":{filter}}",
                        "{\"type\":\"object\",\"properties\":{\"filter\":{\"type\":\"object\",\"properties\":{\"tag\":{\"type\":\"string\"}}}}}",
                        "{\"filter\":{\"tag\":" + smuggle + "}}", "{\"filter\":{\"tag\":\"benign\"}}", Expect.SENT));
    }

    private static String nested(int depth, String leaf) {
        return "{\"k\":".repeat(depth) + leaf + "}".repeat(depth);
    }

    private IConversationMemory memory;
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IResourceClientLibrary resourceClientLibrary;
    private IMemoryItemConverter memoryItemConverter;
    private JsonSerialization jsonSerialization;
    private ApiCallExecutor apiCallExecutor;
    /** Bodies of the requests actually sent, in order. */
    private final List<String> sent = new CopyOnWriteArrayList<>();
    /** Every request built, sent or not. */
    private final List<FakeRequest> built = new CopyOnWriteArrayList<>();
    /** What the API answers (always 200, application/json). */
    private volatile String responseBody = "{\"ok\":true}";

    @BeforeEach
    void setUp() {
        WorkflowTraversal.clearCache();
        memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(AGENT_ID);
        when(memory.getAgentVersion()).thenReturn(1);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        memoryItemConverter = mock(IMemoryItemConverter.class);
        when(memoryItemConverter.convert(any())).thenAnswer(inv -> {
            Map<String, Object> data = new HashMap<>();
            data.put("properties", new HashMap<>(Map.of("role", "user")));
            return data;
        });
        jsonSerialization = new JsonSerialization(new ObjectMapper());

        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(secretResolver.resolveValue("${vault:limit}")).thenReturn("5");
        // Deployment variables as configured: values are not JSON-escaped when they
        // expand, and one may hold a line break.
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            return value == null ? null : value.replace("${vars:greeting}", "Welcome \"back\"").replace("${vars:crlf}", "a\r\nX-Admin: 1");
        });
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        var engine = Engine.builder().addDefaults().strictRendering(false).addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Vault())
                .build();
        var prePostUtils = new PrePostUtils(jsonSerialization, new TemplatingEngine(engine), mock(IDataFactory.class),
                mock(SecretPropertyVault.class));
        IHttpClient httpClient = mock(IHttpClient.class);
        when(httpClient.newRequest(any(URI.class), any())).thenAnswer(inv -> {
            var request = new FakeRequest(inv.getArgument(0), String.valueOf(inv.getArgument(1, Object.class)));
            built.add(request);
            return request;
        });
        apiCallExecutor = new ApiCallExecutor(httpClient, jsonSerialization, mock(IRuntime.class), prePostUtils, globalVariableResolver,
                secretResolver, callerIdentityResolver, new CallerIdentityContext(null, null), new RequestRedactor(callerIdentityResolver),
                mock(ConnectionResolver.class), false, 30_000L, 2_000_000);
    }

    // ------------------------------------------------------------ the table

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void anArgumentNeverChangesTheJsonStructure(Case c) throws Exception {
        ApiCall call = call(c.body());
        if (c.schema() != null) {
            call.setArgumentSchema(c.schema());
        }
        if (c.customize() != null) {
            c.customize().accept(call);
        }
        ToolExecutor executor = executorFor(call);

        String benignResult = executor.execute(toolCall(c.benign()), "m");
        assertFalse(isRefusal(benignResult), c.name() + ": the benign call must be sent, got " + benignResult);
        List<String> benignBodies = List.copyOf(sent);
        assertFalse(benignBodies.isEmpty(), c.name() + ": nothing was sent for the benign call");
        sent.clear();

        String hostileResult = executor.execute(toolCall(c.hostile()), "m");
        if (isRefusal(hostileResult)) {
            assertEquals(Expect.REFUSED, c.expect(), c.name() + ": refused, but expected to be sent safely: " + hostileResult);
            assertTrue(sent.isEmpty(), c.name() + ": a refused call sent a request");
            assertFalse(hostileResult.contains("admin"), c.name() + ": the refusal quotes the value: " + hostileResult);
            return;
        }
        assertEquals(Expect.SENT, c.expect(), c.name() + ": sent, but expected to be refused: " + sent);
        assertEquals(benignBodies.size(), sent.size(), c.name() + ": a different number of requests");
        for (int i = 0; i < sent.size(); i++) {
            JsonNode benign = WHOLE_DOCUMENT.readTree(benignBodies.get(i));
            JsonNode hostile;
            try {
                hostile = WHOLE_DOCUMENT.readTree(sent.get(i));
            } catch (Exception e) {
                fail(c.name() + ": the body sent is not JSON: " + sent.get(i));
                return;
            }
            assertSameStructure(c.name(), benign, hostile);
        }
    }

    // ------------------------------------------------------------ other routes

    @Test
    void forEachOverAnArgumentArrayEscapesEveryItem() throws Exception {
        ApiCall call = call("{\"tag\":\"{item}\",\"id\":{item}}");
        var forEach = new ForEachInstruction();
        forEach.setPathToTargetArray("items");
        forEach.setIterationObjectName("item");
        call.setForEach(forEach);
        ToolExecutor executor = executorFor(call);

        executor.execute(toolCall("{\"items\":[\"a\\\",\\\"admin\\\":true,\\\"b\\\":\\\"\",\"1,2\"]}"), "m");

        assertEquals(2, sent.size(), sent.toString());
        List<String> ids = new ArrayList<>();
        for (String body : sent) {
            JsonNode node = JSON.readTree(body);
            assertEquals(2, node.size(), body);
            assertTrue(node.get("tag").isTextual() && node.get("id").isTextual(), body);
            assertEquals(node.get("tag").asText(), node.get("id").asText(), body);
            ids.add(node.get("id").asText());
        }
        // The items are sent concurrently: compare as a set.
        assertTrue(ids.contains("1,2") && ids.contains("a\",\"admin\":true,\"b\":\""), ids.toString());
    }

    @Test
    void forEachOverAHyphenatedArgumentEscapesTheItem() throws Exception {
        // PathNavigator reads "order-items" as one key; the item is model text.
        ApiCall call = call("{\"tag\":\"{item}\"}");
        var forEach = new ForEachInstruction();
        forEach.setPathToTargetArray("order-items");
        forEach.setIterationObjectName("item");
        call.setForEach(forEach);

        executorFor(call).execute(toolCall("{\"order-items\":[\"a\\\",\\\"admin\\\":true,\\\"b\\\":\\\"\"]}"), "m");

        assertEquals(1, sent.size(), sent.toString());
        JsonNode node = WHOLE_DOCUMENT.readTree(sent.getFirst());
        assertEquals(1, node.size(), sent.getFirst());
        assertEquals("a\",\"admin\":true,\"b\":\"", node.get("tag").asText());
    }

    @Test
    void aForEachWithOneRefusedItemSendsNoItem() throws Exception {
        ApiCall call = call("{\"a\":1}");
        call.getRequest().getHeaders().put("X-Item", "{item}");
        var forEach = new ForEachInstruction();
        forEach.setPathToTargetArray("items");
        forEach.setIterationObjectName("item");
        call.setForEach(forEach);

        String result = executorFor(call).execute(toolCall("{\"items\":[\"fine\",\"a\\r\\nX-Admin: 1\",\"also fine\"]}"), "m");

        assertTrue(isRefusal(result) && result.contains("X-Item"), result);
        assertTrue(sent.isEmpty(), "decided before any item was dispatched: " + sent);
    }

    @Test
    void aLineBreakAVariableExpandsIntoAHeaderIsRefused() throws Exception {
        ApiCall call = call("{\"a\":1}");
        call.getRequest().getHeaders().put("X-Trace", "{trace}");

        String result = executorFor(call).execute(toolCall("{\"trace\":\"${vars:crlf}\"}"), "m");

        assertTrue(isRefusal(result) && result.contains("X-Trace"), result);
        assertTrue(sent.isEmpty());
    }

    @Test
    void aRetrySendsTheLiveBindingNotTheValidatedValue() throws Exception {
        // The first response is saved under the argument's own name; the retry renders
        // that, as it would on the rule path, never the value validated before.
        ApiCall call = call("{\"amount\":{amount}}");
        call.setArgumentSchema(Map.of("type", "object", "properties", Map.of("amount", Map.of("type", "integer"))));
        retryOnceOn200(call, "amount");

        executorFor(call).execute(toolCall("{\"amount\":2}"), "m");

        assertEquals(List.of("{\"amount\":2}", "{\"amount\":{\"ok\":true}}"), sent);
    }

    @Test
    void aRetryRefusedAfterASentAttemptKeepsThatAttemptsOutcome() throws Exception {
        responseBody = "{\"note\":\"${vars:greeting}\"}";
        ApiCall call = call("{\"q\":{q}}");
        retryOnceOn200(call, "q");

        String result = executorFor(call).execute(toolCall("{\"q\":\"first\"}"), "m");

        assertEquals(List.of("{\"q\":\"first\"}"), sent, "the retry was not sent");
        JsonNode answer = JSON.readTree(result);
        assertEquals(200, answer.get("httpCode").asInt(), result);
        assertTrue(answer.get("retryNotSent").asText().contains("was sent"), result);
        assertFalse(result.contains("\"error\""), "not reported as a call that sent nothing: " + result);
    }

    private static void retryOnceOn200(ApiCall call, String responseObjectName) {
        call.setSaveResponse(true);
        call.setResponseObjectName(responseObjectName);
        var postResponse = new HttpPostResponse();
        postResponse.setRetryApiCallInstruction(new RetryApiCallInstruction(1, 0, List.of(200), null));
        call.setPostResponse(postResponse);
    }

    @Test
    void aLineBreakReachingAHeaderThroughAnAliasIsRefused() throws Exception {
        for (String header : List.of("{trace}", "{#let v=trace}{v}{/let}", "{data:trace}")) {
            sent.clear();
            ApiCall call = call("{\"a\":1}");
            call.getRequest().getHeaders().put("X-Trace", header);
            String result = executorFor(call).execute(toolCall("{\"trace\":\"abc\\r\\nX-Admin: 1\"}"), "m");
            assertTrue(isRefusal(result) && result.contains("X-Trace"), header + ": " + result);
            assertTrue(sent.isEmpty(), header);
        }
    }

    @Test
    void theRequestApprovedAtTheGateIsTheRequestSent() throws Exception {
        ApiCall call = call("{\"name\":\"{name}\",\"n\":{n}}");
        var provider = provider(call);
        ToolExecutionRequest request = toolCall("{\"name\":\"x\\\",\\\"admin\\\":true\",\"n\":\"5\"}");

        ResolvedRequest atGate = provider.discover(memory).toolRequestResolvers().get(TOOL).resolve(request);
        provider.discover(memory).executors().get(TOOL).execute(request, "m");

        assertEquals(1, sent.size());
        assertEquals(sent.getFirst(), atGate.body(), "the gate previews the escaped body");
        assertNotNull(atGate.fingerprint());
        FakeRequest sentRequest = built.getLast();
        assertEquals(ResolvedRequest.of(sentRequest.method, sentRequest.uri.toString(), Map.of(), Map.of(), sent.getFirst(), true).fingerprint(),
                atGate.fingerprint(), "the pinned fingerprint covers exactly the executed body");
    }

    @Test
    void aRefusalAtTheGateIsThrownAsItself() throws Exception {
        ApiCall call = call("{\"ids\":[{id ?: 0}]}");
        var provider = provider(call);
        try {
            provider.discover(memory).toolRequestResolvers().get(TOOL).resolve(toolCall("{\"id\":\"1,2,3\"}"));
            fail("expected a refusal");
        } catch (ArgumentRefusedException expected) {
            assertFalse(expected.getMessage().contains("1,2,3"));
        }
        assertTrue(sent.isEmpty());
    }

    @Test
    void optOutAndNonJsonBodiesAreRenderedRaw() throws Exception {
        ApiCall optedOut = call("{\"name\":\"{name}\"}");
        optedOut.setEscapeToolArguments(false);
        executorFor(optedOut).execute(toolCall("{\"name\":\"x\\\",\\\"role\\\":\\\"admin\"}"), "m");
        assertEquals("{\"name\":\"x\",\"role\":\"admin\"}", sent.getFirst(), "escapeToolArguments:false restores raw substitution");

        sent.clear();
        ApiCall form = call("name={name}");
        form.getRequest().setContentType("application/x-www-form-urlencoded");
        executorFor(form).execute(toolCall("{\"name\":\"a\\\"&b=c\"}"), "m");
        assertEquals("name=a\"&b=c", sent.getFirst(), "a body that is not JSON is untouched");
    }

    @Test
    void theRulePathRendersTheConfiguredBodyByteForByte() throws Exception {
        // No tool call, no guard: the executor's own entry point, as ApiCallsTask uses
        // it.
        ApiCall call = call("{\"name\":\"{name}\", \"n\": {n}}");
        Map<String, Object> data = new HashMap<>(Map.of("name", "x\",\"role\":\"admin", "n", "1,2"));
        apiCallExecutor.execute(call, memory, data, "https://api.example");
        assertEquals("{\"name\":\"x\",\"role\":\"admin\", \"n\": 1,2}", sent.getFirst());
    }

    @Test
    void aBodyThatDoesNotMentionAnArgumentIsRenderedAsConfigured() throws Exception {
        ApiCall call = call("{\"role\": \"{properties.role}\" ,\"fixed\":1}");
        executorFor(call).execute(toolCall("{\"unused\":\"x\\\"\"}"), "m");
        assertEquals("{\"role\": \"user\" ,\"fixed\":1}", sent.getFirst());
    }

    @Test
    void numbersKeepTheirDigits() throws Exception {
        ApiCall typedCall = call("{\"amount\":{amount},\"tiny\":{tiny}}");
        typedCall.setArgumentSchema(Map.of("type", "object", "properties",
                Map.of("amount", Map.of("type", "number"), "tiny", Map.of("type", "number"))));
        executorFor(typedCall).execute(toolCall("{\"amount\":12345678901234567890.12,\"tiny\":0.0000001}"), "m");
        assertEquals("{\"amount\":12345678901234567890.12,\"tiny\":0.0000001}", sent.getFirst());

        sent.clear();
        ApiCall untyped = call("{\"amount\":{amount}}");
        executorFor(untyped).execute(toolCall("{\"amount\":\"0.1000000000000000055511151231257827\"}"), "m");
        assertEquals("{\"amount\":0.1000000000000000055511151231257827}", sent.getFirst());
    }

    // ------------------------------------------------------------ helpers

    /**
     * Same node kinds, same author keys (a key may differ only where the benign key
     * is argument-controlled, marked {@code benign}), same array lengths, and
     * author-written strings unchanged (a string may differ only where the benign
     * one is argument-controlled).
     */
    private static void assertSameStructure(String name, JsonNode benign, JsonNode hostile) {
        if (benign.getNodeType() != hostile.getNodeType()) {
            fail(name + ": " + benign.getNodeType() + " became " + hostile.getNodeType() + " — " + hostile);
        }
        if (benign.isObject()) {
            assertEquals(benign.size(), hostile.size(), name + ": fields added or removed — " + hostile);
            Iterator<Map.Entry<String, JsonNode>> b = benign.fields();
            Iterator<Map.Entry<String, JsonNode>> h = hostile.fields();
            while (b.hasNext()) {
                var bf = b.next();
                var hf = h.next();
                if (!bf.getKey().contains("benign")) {
                    assertEquals(bf.getKey(), hf.getKey(), name + ": an author key changed — " + hostile);
                }
                assertSameStructure(name, bf.getValue(), hf.getValue());
            }
        } else if (benign.isArray()) {
            assertEquals(benign.size(), hostile.size(), name + ": elements added or removed — " + hostile);
            for (int i = 0; i < benign.size(); i++) {
                assertSameStructure(name, benign.get(i), hostile.get(i));
            }
        } else if (benign.isTextual() && !benign.asText().contains("benign") && !benign.asText().isEmpty()) {
            assertEquals(benign.asText(), hostile.asText(), name + ": an author-written string changed — " + hostile);
        }
    }

    private static boolean isRefusal(String toolResult) {
        return toolResult != null && (toolResult.contains("NOT sent") || toolResult.contains("NOT_EXECUTED"));
    }

    private static ApiCall call(String body) {
        var request = new Request();
        request.setMethod("post");
        request.setPath("/items");
        request.setContentType("application/json");
        request.setBody(body);
        var apiCall = new ApiCall();
        apiCall.setName(TOOL);
        apiCall.setRequest(request);
        return apiCall;
    }

    private static ToolExecutionRequest toolCall(String arguments) {
        return ToolExecutionRequest.builder().id("call-1").name(TOOL).arguments(arguments).build();
    }

    private ToolExecutor executorFor(ApiCall apiCall) throws Exception {
        ToolExecutor executor = provider(apiCall).discover(memory).executors().get(TOOL);
        assertNotNull(executor);
        return executor;
    }

    private HttpCallToolsProvider provider(ApiCall apiCall) throws Exception {
        WorkflowTraversal.clearCache();
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create(WORKFLOW_URI)));
        when(agentStore.read(AGENT_ID, 1)).thenReturn(agentConfig);
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.httpcalls"));
        step.setConfig(Map.of("uri", HTTPCALLS_URI));
        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of(step));
        when(workflowStore.read("wf-1", 1)).thenReturn(workflowConfig);
        var httpCallsConfig = new ApiCallsConfiguration();
        httpCallsConfig.setTargetServerUrl("https://api.example");
        httpCallsConfig.setHttpCalls(List.of(apiCall));
        when(resourceClientLibrary.getResource(eq(URI.create(HTTPCALLS_URI)), eq(ApiCallsConfiguration.class))).thenReturn(httpCallsConfig);
        return new HttpCallToolsProvider(agentStore, workflowStore, resourceClientLibrary, apiCallExecutor, jsonSerialization,
                memoryItemConverter);
    }

    /** Records the body when the request is SENT, not when it is built. */
    private final class FakeRequest implements IRequest {
        final URI uri;
        final String method;
        String body;
        final Map<String, String> headers = new LinkedHashMap<>();

        FakeRequest(URI uri, String method) {
            this.uri = uri;
            this.method = method;
        }

        @Override
        public IRequest setBasicAuthentication(String username, String password, String realm, boolean preemptive) {
            return this;
        }

        @Override
        public IRequest setQueryParam(String key, String value) {
            return this;
        }

        @Override
        public IRequest setUserAgent(String userAgent) {
            return this;
        }

        @Override
        public IRequest setHttpHeader(String headerName, String value) {
            headers.put(headerName, value);
            return this;
        }

        @Override
        public IRequest setBodyEntity(String content, String encoding, String contentType) {
            this.body = content;
            return this;
        }

        @Override
        public IRequest setMaxResponseSize(int byteSize) {
            return this;
        }

        @Override
        public IRequest setTimeout(long timeout, TimeUnit timeUnit) {
            return this;
        }

        @Override
        public IRequest setFollowRedirects(boolean follow) {
            return this;
        }

        @Override
        public IResponse send() {
            sent.add(body);
            // A plain object, not a mock: forEach items are sent on other threads.
            return new IResponse() {
                @Override
                public String getContentAsString() {
                    return responseBody;
                }

                @Override
                public int getHttpCode() {
                    return 200;
                }

                @Override
                public String getHttpCodeMessage() {
                    return "OK";
                }

                @Override
                public Map<String, String> getHttpHeader() {
                    return Map.of("Content-Type", "application/json");
                }
            };
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(KEY_METHOD, method);
            map.put(KEY_URI, uri.toString());
            if (body != null) {
                map.put(KEY_BODY, body);
            }
            return map;
        }

        @Override
        public void send(ICompleteListener completeListener) {
            sent.add(body);
        }
    }
}
