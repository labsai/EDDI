/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.GroupCompleteEvent;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The group endpoints that run discussion work inline execute on a virtual
 * thread, not on a worker-pool platform thread.
 * <p>
 * A booted HTTP layer is the only place this can be observed: the execution
 * model is decided by Quarkus REST when it dispatches the request, and a direct
 * call on the resource bean runs on whatever thread the test happens to be on.
 * It is also where the one open question is settled — whether Quarkus honours
 * {@code @RunOnVirtualThread} on the implementing method when the JAX-RS method
 * is declared on an interface ({@code IRestGroupConversation}).
 * <p>
 * The group service is replaced by a Mockito stand-in that records
 * {@link Thread#isVirtual()} of the thread each call arrives on and returns a
 * minimal conversation, so no agent, LLM or stored group is involved.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
class GroupEndpointsVirtualThreadIT {

    private static final String GROUP_ID = "6a0f5d783ab1ce8997018ca1";
    private static final String GC_ID = "6a0f5d783ab1ce8997018ca2";

    /** Service method name → whether the call ran on a virtual thread. */
    private final Map<String, Boolean> threadOfCall = new ConcurrentHashMap<>();

    @BeforeEach
    void installRecordingService() {
        // A Mockito subclass, not a JDK proxy: QuarkusMock requires an instance of the
        // bean class itself. Its constructor is never run.
        GroupConversationService recording = Mockito.mock(GroupConversationService.class, invocation -> {
            threadOfCall.put(invocation.getMethod().getName(), Thread.currentThread().isVirtual());
            // A streaming caller waits for the discussion to finish; the real service
            // ends it through the listener, which is what closes the SSE stream.
            for (Object argument : invocation.getArguments()) {
                if (argument instanceof GroupDiscussionEventListener listener) {
                    listener.onGroupComplete(new GroupCompleteEvent(GroupConversationState.COMPLETED, "done"));
                }
            }
            if (invocation.getMethod().getReturnType() == GroupConversation.class) {
                return conversation();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        QuarkusMock.installMockForType(recording, IGroupConversationService.class);
    }

    private static GroupConversation conversation() {
        GroupConversation gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId("anonymous");
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        return gc;
    }

    private void assertRanOnVirtualThread(String serviceMethod) {
        assertEquals(Boolean.TRUE, threadOfCall.get(serviceMethod),
                serviceMethod + " must be reached on a virtual thread (null = never called, false = a worker thread)");
    }

    @Test
    @DisplayName("POST /groups/{id}/conversations runs on a virtual thread")
    void discuss() {
        given().contentType(ContentType.JSON).body(Map.of("question", "q", "userId", "anonymous"))
                .post("/groups/" + GROUP_ID + "/conversations")
                .then().statusCode(lessThan(300));
        assertRanOnVirtualThread("discuss");
    }

    @Test
    @DisplayName("POST …/followup runs on a virtual thread")
    void followUp() {
        given().contentType(ContentType.JSON).body(Map.of("question", "q", "targetAgentId", "6a0f5d783ab1ce8997018ca3"))
                .post("/groups/" + GROUP_ID + "/conversations/" + GC_ID + "/followup")
                .then().statusCode(lessThan(300));
        assertRanOnVirtualThread("followUpWithMember");
    }

    @Test
    @DisplayName("POST …/continue runs on a virtual thread")
    void continueDiscussion() {
        given().contentType(ContentType.JSON).body(Map.of("question", "q"))
                .post("/groups/" + GROUP_ID + "/conversations/" + GC_ID + "/continue")
                .then().statusCode(lessThan(300));
        assertRanOnVirtualThread("continueDiscussion");
    }

    @Test
    @DisplayName("POST …/approve runs on a virtual thread")
    void approve() {
        given().contentType(ContentType.JSON).body(Map.of("decision", Map.of("verdict", "APPROVED")))
                .post("/groups/" + GROUP_ID + "/conversations/" + GC_ID + "/approve")
                .then().statusCode(lessThan(300));
        assertRanOnVirtualThread("resumeDiscussion");
    }

    @Test
    @DisplayName("POST …/approve/stream runs on a virtual thread")
    void approveStreaming() {
        given().contentType(ContentType.JSON).accept("text/event-stream")
                .body(Map.of("decision", Map.of("verdict", "APPROVED")))
                .post("/groups/" + GROUP_ID + "/conversations/" + GC_ID + "/approve/stream")
                .then().statusCode(lessThan(300));
        assertRanOnVirtualThread("resumeDiscussion");
    }

    @Test
    @DisplayName("POST …/human-input runs on a virtual thread")
    void humanInput() {
        given().contentType(ContentType.JSON).body(Map.of("memberId", "m1", "content", "hello"))
                .post("/groups/" + GROUP_ID + "/conversations/" + GC_ID + "/human-input")
                .then().statusCode(lessThan(300));
        assertRanOnVirtualThread("submitHumanInput");
    }
}
