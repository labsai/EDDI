package ai.labs.eddi.testing.internal.impl.rest;

import ai.labs.eddi.testing.ITestCaseStore;
import ai.labs.eddi.testing.internal.impl.TestCaseRuntime;
import ai.labs.eddi.testing.model.TestCaseState;
import ai.labs.eddi.testing.rest.IRestTestCaseRuntime;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestTestCaseRuntime implements IRestTestCaseRuntime {
    private final ITestCaseStore testCaseStore;
    private final TestCaseRuntime testCaseRuntime;

    private static final Logger log = Logger.getLogger(RestTestCaseRuntime.class);

    @Inject
    public RestTestCaseRuntime(ITestCaseStore testCaseStore,
                               TestCaseRuntime testCaseRuntime) {
        this.testCaseStore = testCaseStore;
        this.testCaseRuntime = testCaseRuntime;
    }

    public Response runTestCase(String id) {
        try {
            testCaseRuntime.executeTestCase(id, testCaseStore.loadTestCase(id));
            return Response.accepted().build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        }
    }

    public TestCaseState getTestCaseState(String id) {
        return testCaseStore.getTestCaseState(id);
    }
}
