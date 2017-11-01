package ai.labs.output.model;

import lombok.*;

/**
 * @author ginccc
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
public class QuickReply {
    private String value;
    private String expressions;
    private boolean isDefault;
}
