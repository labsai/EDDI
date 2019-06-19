package ai.labs.persistence.model;

import ai.labs.persistence.IResourceStore;
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