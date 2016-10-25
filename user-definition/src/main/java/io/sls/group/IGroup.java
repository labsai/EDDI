package io.sls.group;

import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
public interface IGroup {
    String getName();

    List<URI> getUsers();
}
