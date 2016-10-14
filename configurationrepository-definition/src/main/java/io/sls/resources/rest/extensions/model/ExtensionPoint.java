package io.sls.resources.rest.extensions.model;

import java.net.URI;

/**
 * User: jarisch
 * Date: 12.09.12
 * Time: 14:55
 */
public class ExtensionPoint {
    private URI namespace;
    private String interfaceClass;
    private String displayKey;

    public URI getNamespace() {
        return namespace;
    }

    public void setNamespace(URI namespace) {
        this.namespace = namespace;
    }

    public String getInterfaceClass() {
        return interfaceClass;
    }

    public void setInterfaceClass(String interfaceClass) {
        this.interfaceClass = interfaceClass;
    }

    public String getDisplayKey() {
        return displayKey;
    }

    public void setDisplayKey(String displayKey) {
        this.displayKey = displayKey;
    }
}
