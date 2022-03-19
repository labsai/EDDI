package ai.labs.eddi.engine.utilities;

import ai.labs.eddi.datastore.model.ResourceId;

import java.net.URI;

/**
 * @author ginccc
 */
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
            try {
                version = Integer.parseInt(queryParamVersion[1].split("=")[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("resourceId must contain a version information. " +
                        "(e.g. <resourceUri>?version=1)");
            }
        }

        return new ResourceId(id, version);
    }
}
