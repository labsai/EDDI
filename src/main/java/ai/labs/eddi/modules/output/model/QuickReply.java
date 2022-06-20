package ai.labs.eddi.modules.output.model;

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
    private Boolean isDefault;

    @Override
    public String toString() {
        return "QuickReply{" +
                "value='" + value + '\'' +
                ", expressions='" + expressions + '\'' +
                ", isDefault=" + isDefault +
                '}';
    }
}
