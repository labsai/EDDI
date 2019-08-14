package ai.labs.channels.differ.model;

import ai.labs.models.Context;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.validation.constraints.NotEmpty;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class DifferBotMapping {
    @NotEmpty
    private List<String> differBotUserIds = new LinkedList<>();
    @NotEmpty
    private String botIntent;
    private Map<String, Context> botContext;
}
