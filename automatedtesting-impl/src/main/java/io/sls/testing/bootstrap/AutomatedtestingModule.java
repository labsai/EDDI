package io.sls.testing.bootstrap;

import com.google.inject.Scopes;
import io.sls.runtime.bootstrap.AbstractBaseModule;
import io.sls.testing.ITestCaseStore;
import io.sls.testing.TestCaseStore;
import io.sls.testing.descriptor.ITestCaseDescriptorStore;
import io.sls.testing.descriptor.impl.TestCaseDescriptorStore;
import io.sls.testing.impl.rest.RestTestCaseRuntime;
import io.sls.testing.impl.rest.RestTestCaseStore;
import io.sls.testing.rest.IRestTestCaseRuntime;
import io.sls.testing.rest.IRestTestCaseStore;

/**
 * @author ginccc
 */
public class AutomatedtestingModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(ITestCaseDescriptorStore.class).to(TestCaseDescriptorStore.class).in(Scopes.SINGLETON);
        bind(ITestCaseStore.class).to(TestCaseStore.class).in(Scopes.SINGLETON);

        bind(IRestTestCaseStore.class).to(RestTestCaseStore.class);
        bind(IRestTestCaseRuntime.class).to(RestTestCaseRuntime.class);
    }
}
