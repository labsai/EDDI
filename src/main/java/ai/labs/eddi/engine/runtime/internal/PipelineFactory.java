package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IExecutablePipeline;
import ai.labs.eddi.engine.runtime.IPipelineFactory;
import ai.labs.eddi.engine.runtime.client.pipelines.IPipelineStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author ginccc
 */
@ApplicationScoped
public class pipelineFactory implements IPipelineFactory {
    private final Map<PackageId, IExecutablePipeline> executablePipelines = new ConcurrentHashMap<>();
    private final IPipelineStoreClientLibrary PipelineStoreClientLibrary;

    @Inject
    public pipelineFactory(IPipelineStoreClientLibrary PipelineStoreClientLibrary) {
        this.PipelineStoreClientLibrary = PipelineStoreClientLibrary;
    }

    @Override
    public IExecutablePipeline getExecutablePipeline(final String packageId, final Integer packageVersion) throws ServiceException {
        PackageId id = new PackageId(packageId, packageVersion);
        if (!executablePipelines.containsKey(id)) {
            synchronized (executablePipelines) {
                IExecutablePipeline executablePipeline = PipelineStoreClientLibrary.getExecutablePipeline(packageId, packageVersion);
                executablePipelines.put(id, executablePipeline);
            }
        }

        return executablePipelines.get(id);
    }

    private class PackageId {
        private final String id;
        private final Integer version;

        private PackageId(String id, Integer version) {
            this.id = id;
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
