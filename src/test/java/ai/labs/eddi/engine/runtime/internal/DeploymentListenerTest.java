package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.model.DeploymentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DeploymentListener}.
 */
@DisplayName("DeploymentListener Tests")
class DeploymentListenerTest {

    private DeploymentListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeploymentListener();
    }

    @Nested
    @DisplayName("registerAgentDeployment")
    class Registration {

        @Test
        @DisplayName("should register and return a CompletableFuture")
        void registersDeployment() {
            CompletableFuture<Void> future = listener.registerAgentDeployment("agent-1", 1);

            assertNotNull(future);
            assertFalse(future.isDone());
        }

        @Test
        @DisplayName("should return same future for duplicate registration")
        void returnsSameForDuplicate() {
            CompletableFuture<Void> f1 = listener.registerAgentDeployment("agent-1", 1);
            CompletableFuture<Void> f2 = listener.registerAgentDeployment("agent-1", 1);

            assertSame(f1, f2);
        }

        @Test
        @DisplayName("should return different futures for different agents")
        void returnsDifferentForDifferentAgents() {
            CompletableFuture<Void> f1 = listener.registerAgentDeployment("agent-1", 1);
            CompletableFuture<Void> f2 = listener.registerAgentDeployment("agent-2", 1);

            assertNotSame(f1, f2);
        }
    }

    @Nested
    @DisplayName("getRegisteredDeploymentEvent")
    class Get {

        @Test
        @DisplayName("should return null for unregistered agent")
        void returnsNullForUnregistered() {
            assertNull(listener.getRegisteredDeploymentEvent("unknown", 1));
        }

        @Test
        @DisplayName("should return registered future")
        void returnsRegisteredFuture() {
            CompletableFuture<Void> registered = listener.registerAgentDeployment("agent-1", 1);
            CompletableFuture<Void> retrieved = listener.getRegisteredDeploymentEvent("agent-1", 1);

            assertSame(registered, retrieved);
        }
    }

    @Nested
    @DisplayName("onDeploymentEvent")
    class Events {

        @Test
        @DisplayName("READY event completes the future successfully")
        void readyCompletesFuture() throws Exception {
            CompletableFuture<Void> future = listener.registerAgentDeployment("agent-1", 1);

            listener.onDeploymentEvent(new DeploymentEvent("agent-1", 1,
                    Deployment.Environment.production, Deployment.Status.READY));

            assertTrue(future.isDone());
            assertFalse(future.isCompletedExceptionally());
            assertNull(future.get()); // Should not throw
        }

        @Test
        @DisplayName("ERROR event completes the future exceptionally")
        void errorCompletesFutureExceptionally() {
            CompletableFuture<Void> future = listener.registerAgentDeployment("agent-1", 1);

            listener.onDeploymentEvent(new DeploymentEvent("agent-1", 1,
                    Deployment.Environment.production, Deployment.Status.ERROR));

            assertTrue(future.isDone());
            assertTrue(future.isCompletedExceptionally());
            assertThrows(ExecutionException.class, future::get);
        }

        @Test
        @DisplayName("READY event removes the future from the map")
        void readyRemovesFuture() {
            listener.registerAgentDeployment("agent-1", 1);

            listener.onDeploymentEvent(new DeploymentEvent("agent-1", 1,
                    Deployment.Environment.production, Deployment.Status.READY));

            assertNull(listener.getRegisteredDeploymentEvent("agent-1", 1));
        }

        @Test
        @DisplayName("ERROR event removes the future from the map")
        void errorRemovesFuture() {
            listener.registerAgentDeployment("agent-1", 1);

            listener.onDeploymentEvent(new DeploymentEvent("agent-1", 1,
                    Deployment.Environment.production, Deployment.Status.ERROR));

            assertNull(listener.getRegisteredDeploymentEvent("agent-1", 1));
        }

        @Test
        @DisplayName("event for unregistered agent does nothing")
        void unregisteredAgentIgnored() {
            // Should not throw
            assertDoesNotThrow(() -> listener.onDeploymentEvent(new DeploymentEvent("unknown", 99,
                    Deployment.Environment.production, Deployment.Status.READY)));
        }

        @Test
        @DisplayName("non-READY, non-ERROR status does nothing")
        void otherStatusIgnored() {
            CompletableFuture<Void> future = listener.registerAgentDeployment("agent-1", 1);

            listener.onDeploymentEvent(new DeploymentEvent("agent-1", 1,
                    Deployment.Environment.production, Deployment.Status.IN_PROGRESS));

            assertFalse(future.isDone()); // Still pending
        }
    }
}
