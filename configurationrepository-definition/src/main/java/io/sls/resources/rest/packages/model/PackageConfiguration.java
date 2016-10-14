package io.sls.resources.rest.packages.model;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * User: jarisch
 * Date: 20.05.12
 * Time: 19:27
 */
public class PackageConfiguration {
    private List<PackageExtension> packageExtensions;

    public PackageConfiguration() {
    }

    public List<PackageExtension> getPackageExtensions() {
        return packageExtensions;
    }

    public void setPackageExtensions(List<PackageExtension> packageExtensions) {
        this.packageExtensions = packageExtensions;
    }

    public static class PackageExtension {
        private URI type;
        private Map<String, Object> extensions;
        private Map<String, Object> config;

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
}
