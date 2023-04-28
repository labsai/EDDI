package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IExecutablePackage;
import ai.labs.eddi.engine.runtime.IPackageFactory;
import ai.labs.eddi.engine.runtime.client.packages.IPackageStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PackageFactory implements IPackageFactory {
    private final Map<PackageId, IExecutablePackage> executablePackages = new ConcurrentHashMap<>();
    private final IPackageStoreClientLibrary packageStoreClientLibrary;

    @Inject
    public PackageFactory(IPackageStoreClientLibrary packageStoreClientLibrary) {
        this.packageStoreClientLibrary = packageStoreClientLibrary;
    }

    @Override
    public IExecutablePackage getExecutablePackage(final String packageId, final Integer packageVersion) throws ServiceException {
        PackageId id = new PackageId(packageId, packageVersion);
        if (!executablePackages.containsKey(id)) {
            synchronized (executablePackages) {
                IExecutablePackage executablePackage = packageStoreClientLibrary.getExecutablePackage(packageId, packageVersion);
                executablePackages.put(id, executablePackage);
            }
        }

        return executablePackages.get(id);
    }

    private class PackageId {
        private String id;
        private Integer version;

        private PackageId(String id, Integer version) {
            this.id = id;
            this.version = version;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PackageId packageId = (PackageId) o;

            return id.equals(packageId.id) && version.equals(packageId.version);

        }

        @Override
        public int hashCode() {
            int result = (id != null ? id.hashCode() : 0);
            result = 31 * result + (version != null ? version.hashCode() : 0);
            return result;
        }
    }
}
