package ai.labs.core.output;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author ginccc
 */

@AllArgsConstructor
@Getter
public class OutputFilter implements IOutputFilter {
    private String key;
    private int occurrence;
}
