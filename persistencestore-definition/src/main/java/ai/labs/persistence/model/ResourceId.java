package ai.labs.persistence.model;

import ai.labs.persistence.IResourceStore;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@Setter
@Getter
@EqualsAndHashCode
@ToString
public class ResourceId implements IResourceStore.IResourceId {
    private String id;
    private Integer version;
}