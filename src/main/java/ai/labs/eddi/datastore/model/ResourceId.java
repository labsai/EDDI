package ai.labs.eddi.datastore.model;

import ai.labs.eddi.datastore.IResourceStore;
import lombok.*;

@AllArgsConstructor
@Setter
@Getter
@EqualsAndHashCode
@ToString
public class ResourceId implements IResourceStore.IResourceId {
    private String id;
    private Integer version;
}