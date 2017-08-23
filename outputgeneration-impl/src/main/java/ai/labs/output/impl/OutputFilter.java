package ai.labs.output.impl;

import ai.labs.output.IOutputFilter;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author ginccc
 */

@AllArgsConstructor
@Getter
class OutputFilter implements IOutputFilter {
    private String action;
    private int occurred;
}
