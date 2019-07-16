package ai.labs.channels.differ.model;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

@Getter
@Setter
public class DifferChannelDefinition {
    private List<DifferEventDefinition> differEventDefinition = new LinkedList<>();
}
