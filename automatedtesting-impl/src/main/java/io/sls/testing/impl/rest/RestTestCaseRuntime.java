package io.sls.testing.impl.rest;

import io.sls.core.service.restinterfaces.IRestInterfaceFactory;
import io.sls.memory.IConversationMemoryStore;
import io.sls.testing.ITestCaseStore;
import io.sls.testing.impl.TestCaseRuntime;
import io.sls.testing.model.TestCaseState;
import io.sls.testing.rest.IRestTestCaseRuntime;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.plugins.guice.RequestScoped;
import org.jboss.resteasy.spi.HttpResponse;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

/**
 * User: jarisch
 * Date: 22.11.12
 * Time: 13:25
 */
@RequestScoped
@Slf4j
public class RestTestCaseRuntime implements IRestTestCaseRuntime {
    private final HttpResponse httpResponse;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final ITestCaseStore testCaseStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final String coreServerURI;

    @Inject
    public RestTestCaseRuntime(@Context HttpResponse httpResponse,
                               IRestInterfaceFactory restInterfaceFactory,
                               ITestCaseStore testCaseStore,
                               IConversationMemoryStore conversationMemoryStore,
                               @Named("coreServerURI") String coreServerURI) {
        this.httpResponse = httpResponse;
        this.restInterfaceFactory = restInterfaceFactory;
        this.testCaseStore = testCaseStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.coreServerURI = coreServerURI;
    }

    public void runTestCase(String id) {
        try {
            httpResponse.setStatus(Response.Status.ACCEPTED.getStatusCode());
            createTestCaseRuntime().executeTestCase(id, testCaseStore.loadTestCase(id));
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        }
    }

    private TestCaseRuntime createTestCaseRuntime() {
        return new TestCaseRuntime(restInterfaceFactory, coreServerURI, testCaseStore, conversationMemoryStore);
    }

    public TestCaseState getTestCaseState(String id) {
        return testCaseStore.getTestCaseState(id);
    }
}
