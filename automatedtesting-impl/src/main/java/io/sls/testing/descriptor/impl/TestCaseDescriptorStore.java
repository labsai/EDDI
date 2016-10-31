package io.sls.testing.descriptor.impl;

import com.mongodb.DB;
import io.sls.group.IGroupStore;
import io.sls.permission.IPermissionStore;
import io.sls.persistence.impl.DescriptorStore;
import io.sls.serialization.IDocumentBuilder;
import io.sls.testing.descriptor.ITestCaseDescriptorStore;
import io.sls.testing.descriptor.model.TestCaseDescriptor;
import io.sls.user.IUserStore;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class TestCaseDescriptorStore extends DescriptorStore<TestCaseDescriptor> implements ITestCaseDescriptorStore {
    @Inject
    public TestCaseDescriptorStore(DB database, IDocumentBuilder documentBuilder,
                                   IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore) {
        super(database, permissionStore, userStore, groupStore, documentBuilder, TestCaseDescriptor.class);
    }
}
