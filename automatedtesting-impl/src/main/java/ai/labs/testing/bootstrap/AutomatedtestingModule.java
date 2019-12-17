package ai.labs.testing.bootstrap;

import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.testing.ITestCaseStore;
import ai.labs.testing.TestCaseStore;
import ai.labs.testing.descriptor.ITestCaseDescriptorStore;
import ai.labs.testing.descriptor.impl.TestCaseDescriptorStore;
import ai.labs.testing.impl.rest.RestTestCaseRuntime;
import ai.labs.testing.impl.rest.RestTestCaseStore;
import ai.labs.testing.rest.IRestTestCaseRuntime;
import ai.labs.testing.rest.IRestTestCaseStore;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class AutomatedtestingModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(ITestCaseDescriptorStore.class).to(TestCaseDescriptorStore.class).in(Scopes.SINGLETON);
        bind(ITestCaseStore.class).to(TestCaseStore.class).in(Scopes.SINGLETON);

        bind(IRestTestCaseStore.class).to(RestTestCaseStore.class).in(Scopes.SINGLETON);
        bind(IRestTestCaseRuntime.class).to(RestTestCaseRuntime.class).in(Scopes.SINGLETON);
    }
}
