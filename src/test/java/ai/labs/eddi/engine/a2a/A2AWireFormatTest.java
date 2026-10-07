/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.A2AMessage;
import ai.labs.eddi.engine.a2a.A2AModels.A2ATask;
import ai.labs.eddi.engine.a2a.A2AModels.Artifact;
import ai.labs.eddi.engine.a2a.A2AModels.Dialect;
import ai.labs.eddi.engine.a2a.A2AModels.InvalidA2ARequestException;
import ai.labs.eddi.engine.a2a.A2AModels.Part;
import ai.labs.eddi.engine.a2a.A2AModels.TaskState;
import ai.labs.eddi.engine.a2a.A2AModels.TaskStatus;
import ai.labs.eddi.engine.a2a.A2AWireFormat.Operation;
import ai.labs.eddi.engine.a2a.A2AWireFormat.SendRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the three A2A wire dialects, against spec-shaped JSON
 * fixtures under {@code src/test/resources/tests/a2a}. The 1.0 fixtures follow
 * the examples of the A2A specification v1.0.1; the 0.3 ones its predecessor;
 * the legacy one is what EDDI documented (and older EDDI clients send) for
 * {@code tasks/send}.
 */
@DisplayName("A2A wire format")
class A2AWireFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixture(String name) throws Exception {
        try (InputStream in = A2AWireFormatTest.class.getResourceAsStream("/tests/a2a/" + name)) {
            return MAPPER.readValue(in, Map.class);
        }
    }

    private static JsonNode fixtureTree(String name) throws Exception {
        try (InputStream in = A2AWireFormatTest.class.getResourceAsStream("/tests/a2a/" + name)) {
            return MAPPER.readTree(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> request) {
        return (Map<String, Object>) request.get("params");
    }

    @Nested
    @DisplayName("method names")
    class Methods {

        @Test
        void everyDialectsSendResolves() {
            assertEquals(new A2AWireFormat.MethodRef(Operation.SEND, Dialect.V1_0), A2AWireFormat.resolveMethod("SendMessage"));
            assertEquals(new A2AWireFormat.MethodRef(Operation.SEND, Dialect.V0_3), A2AWireFormat.resolveMethod("message/send"));
            assertEquals(new A2AWireFormat.MethodRef(Operation.SEND, Dialect.LEGACY), A2AWireFormat.resolveMethod("tasks/send"));
            assertEquals(new A2AWireFormat.MethodRef(Operation.STREAM, Dialect.V1_0), A2AWireFormat.resolveMethod("SendStreamingMessage"));
            assertEquals(new A2AWireFormat.MethodRef(Operation.STREAM, Dialect.V0_3), A2AWireFormat.resolveMethod("message/stream"));
            assertEquals(new A2AWireFormat.MethodRef(Operation.GET, Dialect.V1_0), A2AWireFormat.resolveMethod("GetTask"));
            assertEquals(new A2AWireFormat.MethodRef(Operation.CANCEL, Dialect.V0_3), A2AWireFormat.resolveMethod("tasks/cancel"));
        }

        @Test
        void unknownMethodIsNull() {
            assertNull(A2AWireFormat.resolveMethod("tasks/explode"));
            assertNull(A2AWireFormat.resolveMethod(null));
        }
    }

    @Nested
    @DisplayName("reading requests")
    class Requests {

        @Test
        @DisplayName("1.0 SendMessage: text and data parts, context, returnImmediately, historyLength")
        void v10() throws Exception {
            SendRequest request = A2AWireFormat.parseSend(params(fixture("v1_0-send-message-request.json")), Dialect.V1_0);

            assertEquals("What is the weather?\n{\"city\":\"Vienna\"}", request.text());
            assertEquals("msg-1", request.messageId());
            assertEquals("ctx-1", request.contextId());
            assertNull(request.taskId());
            assertTrue(request.returnImmediately());
            assertEquals(0, request.historyLength());
            assertEquals(Dialect.V1_0, request.dialect());
        }

        @Test
        @DisplayName("0.3 message/send: kind parts, blocking=false, a continued task")
        void v03() throws Exception {
            SendRequest request = A2AWireFormat.parseSend(params(fixture("v0_3-message-send-request.json")), Dialect.V0_3);

            assertEquals("Track order #12345", request.text());
            assertEquals("task-continued", request.taskId());
            assertTrue(request.returnImmediately());
            assertNull(request.legacyTaskId());
        }

        @Test
        @DisplayName("pre-0.2 tasks/send: type parts, peer-chosen id, contextId next to the message")
        void legacy() throws Exception {
            SendRequest request = A2AWireFormat.parseSend(params(fixture("legacy-tasks-send-request.json")), Dialect.LEGACY);

            assertEquals("Track order #12345", request.text());
            assertEquals("task-1", request.legacyTaskId());
            assertEquals("ctx-legacy", request.contextId());
            assertNull(request.taskId());
            assertFalse(request.returnImmediately());
        }

        @Test
        void onlyFileParts_isContentTypeNotSupported() {
            Map<String, Object> params = Map.of("message",
                    Map.of("parts", List.of(Map.of("url", "https://x/y.pdf", "mediaType", "application/pdf"))));

            var e = assertThrows(InvalidA2ARequestException.class, () -> A2AWireFormat.parseSend(params, Dialect.V1_0));
            assertEquals(A2AModels.ERROR_CONTENT_TYPE_NOT_SUPPORTED, e.getCode());
        }

        @Test
        void missingMessageOrTextIsInvalidParams() {
            var noMessage = assertThrows(InvalidA2ARequestException.class, () -> A2AWireFormat.parseSend(Map.of(), Dialect.V1_0));
            assertEquals(A2AModels.ERROR_INVALID_PARAMS, noMessage.getCode());
            assertThrows(InvalidA2ARequestException.class,
                    () -> A2AWireFormat.parseSend(Map.of("message", Map.of("parts", List.of(Map.of("text", "  ")))), Dialect.V1_0));
            assertThrows(InvalidA2ARequestException.class, () -> A2AWireFormat.parseSend(null, Dialect.V1_0));
        }

        @Test
        void taskIdOfGetAndCancel() {
            assertEquals("t1", A2AWireFormat.requireTaskId(Map.of("id", "t1")));
            assertThrows(InvalidA2ARequestException.class, () -> A2AWireFormat.requireTaskId(Map.of("id", " ")));
            assertThrows(InvalidA2ARequestException.class, () -> A2AWireFormat.requireTaskId(null));
        }
    }

    @Nested
    @DisplayName("writing results")
    class Results {

        private A2ATask completed() {
            String id = "task-uuid";
            return new A2ATask(id, "ctx-1", new TaskStatus(TaskState.completed, null, T0),
                    List.of(new A2AMessage(id + "-user", A2AMessage.ROLE_USER, List.of(Part.textPart("What is the weather?")), id, "ctx-1"),
                            new A2AMessage(id + "-agent", A2AMessage.ROLE_AGENT, List.of(Part.textPart("Sunny, 75°F")), id, "ctx-1")),
                    List.of(new Artifact("response", "response", List.of(Part.textPart("Sunny, 75°F")))));
        }

        @Test
        @DisplayName("1.0 SendMessage result matches the spec's shape exactly — nothing a strict parser would reject")
        void v10SendResult() throws Exception {
            JsonNode rendered = MAPPER.valueToTree(A2AWireFormat.sendResult(completed(), Dialect.V1_0));

            assertEquals(fixtureTree("v1_0-send-message-response.json"), rendered);
        }

        @Test
        @DisplayName("0.3 task: kind discriminators, kebab-case state, status message")
        void v03Task() throws Exception {
            String id = "task-03";
            var task = new A2ATask(id, "ctx-03",
                    new TaskStatus(TaskState.input_required,
                            new A2AMessage(id + "-status", A2AMessage.ROLE_AGENT, List.of(Part.textPart("Waiting for approval")), id, "ctx-03"), T0),
                    List.of(new A2AMessage(id + "-user", A2AMessage.ROLE_USER, List.of(Part.textPart("Refund order #12345")), id, "ctx-03")),
                    null);

            assertEquals(fixtureTree("v0_3-task-input-required.json"), MAPPER.valueToTree(A2AWireFormat.sendResult(task, Dialect.V0_3)));
        }

        @Test
        @DisplayName("legacy: the 0.3 shape, with the old type discriminator added to every part")
        @SuppressWarnings("unchecked")
        void legacyParts() {
            Map<String, Object> rendered = A2AWireFormat.task(completed(), Dialect.LEGACY);

            var artifact = ((List<Map<String, Object>>) rendered.get("artifacts")).getFirst();
            var part = ((List<Map<String, Object>>) artifact.get("parts")).getFirst();
            assertEquals("text", part.get("type"));
            assertEquals("text", part.get("kind"));
            assertEquals("completed", ((Map<String, Object>) rendered.get("status")).get("state"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void streamingEvents_v10_areWrapped_v03_carryKindAndFinal() {
            var task = completed();
            var artifact = task.artifacts().getFirst();

            var v10Status = A2AWireFormat.statusUpdate(task, true, Dialect.V1_0);
            assertEquals(List.of("statusUpdate"), List.copyOf(v10Status.keySet()));
            assertFalse(((Map<String, Object>) v10Status.get("statusUpdate")).containsKey("final"));
            var v10Artifact = (Map<String, Object>) A2AWireFormat.artifactUpdate(task, artifact, true, false, Dialect.V1_0).get("artifactUpdate");
            assertEquals(true, v10Artifact.get("append"));
            assertEquals("ctx-1", v10Artifact.get("contextId"));

            var v03Status = A2AWireFormat.statusUpdate(task, true, Dialect.V0_3);
            assertEquals("status-update", v03Status.get("kind"));
            assertEquals(true, v03Status.get("final"));
            assertEquals("artifact-update", A2AWireFormat.artifactUpdate(task, artifact, false, true, Dialect.V0_3).get("kind"));
            assertEquals("task", A2AWireFormat.streamTask(task, Dialect.V0_3).get("kind"));
            assertTrue(A2AWireFormat.streamTask(task, Dialect.V1_0).containsKey("task"));
        }

        @Test
        void dataPartsRenderAsData() {
            assertEquals(Map.of("data", Map.of("k", 1)), A2AWireFormat.part(Part.dataPart(Map.of("k", 1)), Dialect.V1_0));
            assertEquals(Map.of("kind", "data", "data", Map.of("k", 1)), A2AWireFormat.part(Part.dataPart(Map.of("k", 1)), Dialect.V0_3));
        }
    }

    @Nested
    @DisplayName("task states")
    class States {

        @Test
        void wireNamesPerDialect() {
            assertEquals("TASK_STATE_INPUT_REQUIRED", TaskState.input_required.wireName(Dialect.V1_0));
            assertEquals("input-required", TaskState.input_required.wireName(Dialect.V0_3));
            assertEquals("input-required", TaskState.input_required.wireName(Dialect.LEGACY));
            assertEquals("TASK_STATE_UNSPECIFIED", TaskState.unknown.wireName(Dialect.V1_0));
        }

        @Test
        void fromWireReadsEveryDialect() {
            assertEquals(TaskState.input_required, TaskState.fromWire("TASK_STATE_INPUT_REQUIRED"));
            assertEquals(TaskState.input_required, TaskState.fromWire("input-required"));
            assertEquals(TaskState.canceled, TaskState.fromWire("cancelled"));
            assertEquals(TaskState.unknown, TaskState.fromWire("bogus"));
            assertEquals(TaskState.unknown, TaskState.fromWire(null));
        }

        @Test
        void terminalStates() {
            assertTrue(TaskState.completed.isTerminal());
            assertTrue(TaskState.failed.isTerminal());
            assertTrue(TaskState.canceled.isTerminal());
            assertTrue(TaskState.rejected.isTerminal());
            assertFalse(TaskState.input_required.isTerminal());
            assertFalse(TaskState.working.isTerminal());
        }
    }
}
