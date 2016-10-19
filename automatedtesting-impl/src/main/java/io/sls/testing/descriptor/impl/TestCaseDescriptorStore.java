package io.sls.testing.descriptor.impl;

import com.mongodb.DB;
import io.sls.group.IGroupStore;
import io.sls.permission.IPermissionStore;
import io.sls.persistence.impl.DescriptorStore;
import io.sls.serialization.JSONSerialization;
import io.sls.testing.descriptor.ITestCaseDescriptorStore;
import io.sls.testing.descriptor.model.TestCaseDescriptor;
import io.sls.user.IUserStore;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;

/**
 * User: jarisch
 * Date: 22.11.12
 * Time: 15:38
 */
public class TestCaseDescriptorStore extends DescriptorStore<TestCaseDescriptor> implements ITestCaseDescriptorStore {
    @Inject
    public TestCaseDescriptorStore(DB database, IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore) {
        super(database, permissionStore, userStore, groupStore, doc -> JSONSerialization.deserialize(doc, new TypeReference<TestCaseDescriptor>() {}));
    }
}
