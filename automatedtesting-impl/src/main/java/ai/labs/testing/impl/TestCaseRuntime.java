package ai.labs.testing.impl;

import ai.labs.memory.IConversationMemoryStore;
import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.models.ConversationState;
import ai.labs.models.Deployment;
import ai.labs.rest.restinterfaces.IRestBotAdministration;
import ai.labs.rest.restinterfaces.IRestBotEngine;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.ThreadContext;
import ai.labs.testing.ITestCaseStore;
import ai.labs.testing.model.TestCase;
import ai.labs.testing.model.TestCaseState;
import ai.labs.utilities.RuntimeUtilities;

import javax.inject.Inject;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.TimeoutHandler;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * @author ginccc
 */
public class TestCaseRuntime {
    private final IRestInterfaceFactory restInterfaceFactory;
    private final ITestCaseStore testCaseStore;
    private final IConversationMemoryStore conversationMemoryStore;

    @Inject
    public TestCaseRuntime(IRestInterfaceFactory restInterfaceFactory,
                           ITestCaseStore testCaseStore, IConversationMemoryStore conversationMemoryStore) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.testCaseStore = testCaseStore;
        this.conversationMemoryStore = conversationMemoryStore;
    }

    public void executeTestCase(final String id, final TestCase testCase) {
        SystemRuntime.getRuntime().submitCallable((Callable<Void>) () -> {
            try {
                testCaseStore.setTestCaseState(id, TestCaseState.IN_PROGRESS);

                if (!isBotDeployed(testCase.getBotId(), testCase.getBotVersion())) {
                    deployBot(testCase.getBotId(), testCase.getBotVersion());
                }

                ConversationMemorySnapshot actual = runTestCase(testCase.getBotId(), testCase);
                testCase.setActual(actual);
                testCase.setLastRun(new Date(System.currentTimeMillis()));
                testCase.setTestCaseState(testCase.getExpected().equals(testCase.getActual()) ? TestCaseState.SUCCESS : TestCaseState.FAILED);
                testCaseStore.storeTestCase(id, testCase);
            } catch (Exception e) {
                testCaseStore.setTestCaseState(id, TestCaseState.ERROR);
                throw e;
            }
            return null;
        }, ThreadContext.getResources());
    }

    private boolean isBotDeployed(String botId, Integer botVersion) throws Exception {
        IRestBotAdministration restBotAdministration = restInterfaceFactory.get(IRestBotAdministration.class);
        String deploymentStatus;
        do {
            deploymentStatus = restBotAdministration.getDeploymentStatus(Deployment.Environment.test, botId, botVersion);
            if (Objects.equals(deploymentStatus, Deployment.Status.IN_PROGRESS.toString())) {
                Thread.sleep(1000);
            } else {
                break;
            }
        } while (true);

        return !Objects.equals(deploymentStatus, Deployment.Status.NOT_FOUND.toString());
    }

    private void deployBot(String botId, Integer botVersion) throws Exception {
        IRestBotAdministration restBotAdministration = restInterfaceFactory.get(IRestBotAdministration.class);
        restBotAdministration.deployBot(Deployment.Environment.test, botId, botVersion, false);
        while (true) {
            //wait until deployment has finished
            if (!Objects.equals(restBotAdministration.getDeploymentStatus(Deployment.Environment.test, botId, botVersion), Deployment.Status.IN_PROGRESS.toString())) {
                break;
            } else {
                Thread.sleep(1000);
            }
        }
    }

    private ConversationMemorySnapshot runTestCase(String botId, TestCase testCase) throws Exception {
        IRestBotEngine botEngine = restInterfaceFactory.get(IRestBotEngine.class);

        Response ConversationResponse = botEngine.startConversation(Deployment.Environment.test, botId, "testCaseRunner");
        URI conversationURI = ConversationResponse.getLocation();
        String conversationURIPath = conversationURI.getPath();
        String conversationId = conversationURIPath.substring(conversationURIPath.lastIndexOf("/") + 1);
        ConversationMemorySnapshot expected = testCase.getExpected();
        List<ConversationMemorySnapshot.ConversationStepSnapshot> expectedConversationSteps = expected.getConversationSteps();
        //we skip the first one, since the initial run has already been done at this point (at startConversation)
        for (int i = 1; i < expectedConversationSteps.size(); i++) {
            ConversationMemorySnapshot.ConversationStepSnapshot expectedConversationStep = expectedConversationSteps.get(i);
            String input = getFirstInput(expectedConversationStep);
            if (RuntimeUtilities.isNullOrEmpty(input)) {
                input = " ";
            }
            botEngine.say(Deployment.Environment.test, botId, conversationId,
                    true, false,
                    Collections.emptyList(), input, new MockAsyncResponse());
            while (botEngine.getConversationState(Deployment.Environment.test, conversationId) == ConversationState.IN_PROGRESS) {
                Thread.sleep(1000);
            }
        }

        return conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
    }

    private String getFirstInput(ConversationMemorySnapshot.ConversationStepSnapshot conversationStep) {
        for (ConversationMemorySnapshot.PackageRunSnapshot packageRunSnapshot : conversationStep.getPackages()) {
            for (ConversationMemorySnapshot.ResultSnapshot resultSnapshot : packageRunSnapshot.getLifecycleTasks()) {
                if (resultSnapshot.getKey().startsWith("input")) {
                    return resultSnapshot.getResult().toString();
                }
            }
        }

        return null;
    }

    private static class MockAsyncResponse implements AsyncResponse {

        @Override
        public boolean resume(Object response) {
            return false;
        }

        @Override
        public boolean resume(Throwable response) {
            return false;
        }

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public boolean cancel(int retryAfter) {
            return false;
        }

        @Override
        public boolean cancel(Date retryAfter) {
            return false;
        }

        @Override
        public boolean isSuspended() {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean setTimeout(long time, TimeUnit unit) {
            return false;
        }

        @Override
        public void setTimeoutHandler(TimeoutHandler handler) {

        }

        @Override
        public Collection<Class<?>> register(Class<?> callback) {
            return null;
        }

        @Override
        public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>[] callbacks) {
            return null;
        }

        @Override
        public Collection<Class<?>> register(Object callback) {
            return null;
        }

        @Override
        public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks) {
            return null;
        }
    }
}

