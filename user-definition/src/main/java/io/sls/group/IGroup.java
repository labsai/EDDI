package io.sls.group;

import java.net.URI;
import java.util.List;

/**
 * User: jarisch
 * Date: 29.08.12
 * Time: 14:45
 */
public interface IGroup {
    String getName();

    List<URI> getUsers();
}
