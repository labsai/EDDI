package ai.labs.eddi.engine.model;

import lombok.Getter;
import lombok.Setter;

import java.net.URI;
import java.util.Date;

/**
 * @author ginccc
 */

@Getter
@Setter
public class ResourceDescriptor {
    private URI resource;
    private URI createdBy;
    private Date createdOn;
    private URI lastModifiedBy;
    private Date lastModifiedOn;
    private boolean deleted;
}
