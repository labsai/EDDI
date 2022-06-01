package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TextOutputItem extends OutputItem {
    public TextOutputItem(String text) {
        this.text = text;
    }

    private String text;
    private Integer delay;
}
