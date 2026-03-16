package ai.labs.eddi.configs.packages.model;


import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class PackageConfiguration {
    private List<PackageExtension> packageExtensions = new LinkedList<>();

    public static class PackageExtension {
        private URI type;
        private Map<String, Object> extensions = new HashMap<>();
        private Map<String, Object> config = new HashMap<>();

        public URI getType() {
            return type;
        }

        public void setType(URI type) {
            this.type = type;
        }

        public Map<String, Object> getExtensions() {
            return extensions;
        }

        public void setExtensions(Map<String, Object> extensions) {
            this.extensions = extensions;
        }

        public Map<String, Object> getConfig() {
            return config;
        }

        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }
    }

    public PackageConfiguration() {
    }

    public List<PackageExtension> getPackageExtensions() {
        return packageExtensions;
    }

    public void setPackageExtensions(List<PackageExtension> packageExtensions) {
        this.packageExtensions = packageExtensions;
    }
}
