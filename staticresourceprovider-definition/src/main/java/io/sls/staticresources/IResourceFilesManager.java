package io.sls.staticresources;

import java.util.List;

/**
 * User: jarisch
 * Date: 21.11.12
 * Time: 17:51
 */
public interface IResourceFilesManager {
    Options getOptions();

    void reloadResourceFiles();

    void includeJavascriptFile(StringBuilder jsScriptTags, String relativePath);

    List<IResourceDirectory> getResourceDirectories();

    IResourceDirectory getResourceDirectory(String keyIdentifier, String targetDevice);

    class Options {
        private String scheme;
        private String host;
        private Integer port;

        private String baseResourcePath;
        private String baseWebPath;

        private boolean mergeResourceFiles;
        private boolean addFingerprintToResources;
        private boolean alwaysReloadResourcesFile;

        public Options(String scheme, String host, Integer port, String baseResourcePath, String baseWebPath, boolean mergeResourceFiles, boolean addFingerprintToResources, boolean alwaysReloadResourcesFile) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.baseResourcePath = baseResourcePath;
            this.baseWebPath = baseWebPath;
            this.mergeResourceFiles = mergeResourceFiles;
            this.addFingerprintToResources = addFingerprintToResources;
            this.alwaysReloadResourcesFile = alwaysReloadResourcesFile;
        }

        public String getScheme() {
            return scheme;
        }

        public String getHost() {
            return host;
        }

        public Integer getPort() {
            return port;
        }

        public String getBaseResourcePath() {
            return baseResourcePath;
        }

        public String getBaseWebPath() {
            return baseWebPath;
        }

        public boolean isMergeResourceFiles() {
            return mergeResourceFiles;
        }

        public boolean isAddFingerprintToResources() {
            return addFingerprintToResources;
        }

        public boolean alwaysReloadResourcesFile() {
            return alwaysReloadResourcesFile;
        }
    }
}
