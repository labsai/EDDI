package ai.labs.parser.rest.model;

import ai.labs.expressions.Expressions;
import lombok.*;

/**
 * @author ginccc
 */

@Setter
@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class Solution {
    private Expressions expressions;
}
