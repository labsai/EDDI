package ai.labs.channels.differ.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class CommandInfo {
    private String exchange;
    private String routingKey;
    private ICommand command;
}
