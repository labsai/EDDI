package ai.labs.output.impl;

import ai.labs.output.IQuickReply;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author ginccc
 */
@AllArgsConstructor
@Getter
@Setter
public class QuickReply implements IQuickReply {
    private String value;
    private String expressions;
}
