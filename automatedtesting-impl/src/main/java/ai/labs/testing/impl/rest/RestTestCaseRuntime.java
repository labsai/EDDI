package ai.labs.testing.impl.rest;

import ai.labs.memory.IConversationMemoryStore;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.testing.ITestCaseStore;
import ai.labs.testing.impl.TestCaseRuntime;
import ai.labs.testing.model.TestCaseState;
import ai.labs.testing.rest.IRestTestCaseRuntime;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Slf4j
public class RestTestCaseRuntime implements IRestTestCaseRuntime {
    private final IRestInterfaceFactory restInterfaceFactory;
    private final ITestCaseStore testCaseStore;
    private final IConversationMemoryStore conversationMemoryStore;

    @Inject
    public RestTestCaseRuntime(IRestInterfaceFactory restInterfaceFactory,
                               ITestCaseStore testCaseStore,
                               IConversationMemoryStore conversationMemoryStore) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.testCaseStore = testCaseStore;
        this.conversationMemoryStore = conversationMemoryStore;
    }

    public Response runTestCase(String id) {
        try {
            createTestCaseRuntime().executeTestCase(id, testCaseStore.loadTestCase(id));
            return Response.accepted().build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        }
    }

    private TestCaseRuntime createTestCaseRuntime() {
        return new TestCaseRuntime(restInterfaceFactory, testCaseStore, conversationMemoryStore);
    }

    public TestCaseState getTestCaseState(String id) {
        return testCaseStore.getTestCaseState(id);
    }
}
