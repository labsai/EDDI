package io.sls.resources.rest.documentdescriptor.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * User: jarisch
 * Date: 06.09.12
 * Time: 09:32
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class SimpleDocumentDescriptor {
    private String name;
    private String description;

}
