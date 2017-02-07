package ai.labs.utilities;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;

/**
 * @author ginccc
 */
@Slf4j
public class URIUtilities {

    public static ResourceId extractResourceId(URI uri) {
        String uriString = uri.toString();
        if (uriString.endsWith("/")) {
            uriString = uriString.substring(0, uriString.length() - 1);
        }

        String[] split = uriString.split("/");
        String id = split[split.length - 1].split("\\?")[0];

        String[] queryParamVersion = uriString.split("\\?");
        Integer version = -1;
        if (queryParamVersion.length > 1) {
            version = Integer.parseInt(queryParamVersion[1].split("=")[1]);
        }

        return new ResourceId(id, version);
    }

    public static class ResourceId {
        private String id;
        private Integer version;

        ResourceId(String id, Integer version) {
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
    }

}
